package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A person's statements: taking one records their due-total on the sheet's today (or a date before it), binds each of
 * their counting claims to the due figure it shows then, and leaves the rest unbound. A steward may
 * cancel the last one: the claims it bound are live again, and the person shows the one before it.
 */
@Service
public class StatementService {

    /** Shown in place of a due-total when the grade has no rate. */
    private static final String NO_RATE = "n/a";

    /** The person as stated, with all of their claims, newest first. */
    public record Stated(Person person, List<Claim> claims) {
    }

    private final PersonRepository people;
    private final ClaimRepository claims;
    private final Dues dues;
    private final SheetHistory history;
    private final SheetService sheets;
    private final Rollups rollups;
    private final StatementRepository statements;
    private final RoleService roles;
    private final Audit audit;

    public StatementService(PersonRepository people, ClaimRepository claims, Dues dues, SheetHistory history,
            SheetService sheets, Rollups rollups, StatementRepository statements, RoleService roles, Audit audit) {
        this.rollups = rollups;
        this.statements = statements;
        this.roles = roles;
        this.people = people;
        this.claims = claims;
        this.dues = dues;
        this.history = history;
        this.sheets = sheets;
        this.audit = audit;
    }

    /**
     * Take a statement for a person on the sheet, as of a date on or before the sheet's today (null:
     * now). Their claims that counted at the end of that date are bound to the figures they had then,
     * every change made while the sheet's today was after it left out and that date standing in for
     * the sheet's today; every other claim of theirs is left unbound. Refused for a date after the
     * sheet's today, or one the sheet or the person was not yet kept on.
     */
    @Transactional
    public Stated take(String sheetId, String user, long personId, LocalDate asOf) {
        LocalDate today = sheets.today(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (asOf != null && asOf.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A statement cannot be taken as of a date after today.");
        }
        LocalDate day = asOf != null ? asOf : today;
        // On the sheet's today every change counts: the sheet as it stands now. Before it, as kept then.
        Person then;
        // Their claims as they stood then, by id.
        Map<Long, Claim> theirsThen = new LinkedHashMap<>();
        Dues.Basis basis;
        Sheet sheet;
        if (day.equals(today)) {
            then = person;
            claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId).forEach(c -> theirsThen.put(c.getId(), c));
            basis = dues.live(sheetId);
            sheet = sheets.load(sheetId);
        } else {
            SheetHistory.AsOf kept = history.at(sheetId, day).orElseThrow(() -> notYet(day));
            then = kept.people().get(personId);
            if (then == null) {
                throw notYet(day);
            }
            kept.claims().forEach((id, c) -> {
                if (c.getPersonId() == personId) {
                    theirsThen.put(id, c);
                }
            });
            basis = kept.basis();
            sheet = kept.sheet();
        }
        Rollups.Rollup before = rollups.take(sheetId);
        Map<Long, BigDecimal> bound = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean figured = false;
        int counted = 0;
        for (Map.Entry<Long, Claim> entry : theirsThen.entrySet()) {
            Claim claim = entry.getValue();
            // As in the live totals: an OPEN or PENDING claim dated within a hold, or of a person whose
            // retirement date is reached, and not bound, is left
            // out, as is a claim deferred on the day.
            if (!then.countsLive(claim.getStatus(), claim.getDate(), claim.isStated(), claim.isDeferredOn(day), day)) {
                continue;
            }
            // The statement binds the rate figure; the late charge and floor stay live, but count in its total.
            BigDecimal figure = dues.figure(claim, then.gradeOn(claim.getDate()), basis);
            figured |= figure != null;
            bound.put(entry.getKey(), figure);
            counted++;
            BigDecimal due = dues.due(claim, then.gradeOn(claim.getDate()), basis);
            if (due != null) {
                total = total.add(due);
            }
        }
        // As on the screen: n/a when the grade in force on the day has no rate, unless a claim has a figure.
        if (basis.rateInForce(then.gradeOn(day), day).isEmpty() && !figured) {
            total = null;
        }
        // A due-total is at most the cap in force on the day. The claims they have now are bound by id.
        total = sheet.capped(total);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        for (Claim claim : theirs) {
            if (bound.containsKey(claim.getId())) {
                claim.bind(bound.get(claim.getId()));
            } else {
                claim.unbind();
            }
        }
        person.stated(total, day);
        Person saved = people.saveAndFlush(person);
        statements.saveAndFlush(new Statement(sheetId, saved.getId(), saved.getStatementCount(), total, day));
        List<Claim> stated = claims.saveAllAndFlush(theirs);
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " due-total=" + (total != null ? total.toPlainString() : NO_RATE) + " claims=" + counted
                + (asOf != null ? " asof=" + asOf : ""));
        rollups.audit(sheetId, user, before);
        return new Stated(saved, stated);
    }

    /** Refused: the sheet, or the person, was not yet kept at the end of that date. */
    private static ResponseStatusException notYet(LocalDate day) {
        return new ResponseStatusException(HttpStatus.CONFLICT, "The person was not on the sheet on " + day + ".");
    }

    /**
     * Cancel a person's last statement: the claims it bound are live again (a figure an earlier
     * statement bound is not restored), and the person shows the statement before it, or none. Only a
     * steward cancels; refused when the person has no statement.
     */
    @Transactional
    public Stated cancel(String sheetId, String user, long personId) {
        sheets.lock(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward cancels a statement.");
        }
        if (person.getStatementCount() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, person.getName() + " has no statement to cancel.");
        }
        Rollups.Rollup before = rollups.take(sheetId);
        BigDecimal cancelled = person.getStatementTotal();
        List<Statement> taken = statements.findBySheetIdAndPersonIdOrderBySeqDesc(sheetId, personId);
        if (!taken.isEmpty() && taken.get(0).getSeq() == person.getStatementCount()) {
            statements.delete(taken.remove(0));
        }
        // The one before it, when it was recorded.
        Statement previous = taken.isEmpty() || taken.get(0).getSeq() != person.getStatementCount() - 1
                ? null : taken.get(0);
        person.unstated(previous == null ? null : previous.getTotal(), previous == null ? null : previous.getTakenOn());
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        theirs.forEach(Claim::unbind);
        Person saved = people.saveAndFlush(person);
        List<Claim> live = claims.saveAllAndFlush(theirs);
        audit.recordOnCommit("STATEMENT_CANCELLED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " due-total=" + (cancelled != null ? cancelled.toPlainString() : NO_RATE));
        rollups.audit(sheetId, user, before);
        return new Stated(saved, live);
    }
}
