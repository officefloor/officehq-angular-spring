package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimLog;
import net.officefloor.hq.app.claim.ClaimLogRepository;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes statements of people. A statement records the person's due-total and binds each of their counting
 * claims to the due it shows then, late charge included (a claim already bound keeps its bound due); their other
 * claims are left unbound, so their dues are live again.
 */
@Service
public class StatementService {

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimLogRepository claimLogs;
    private final RateService rates;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final RoleService roles;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            ClaimLogRepository claimLogs, RateService rates, SheetService sheets, Audit audit, RollupService rollup,
            RoleService roles) {
        this.roles = roles;
        this.claimLogs = claimLogs;
        this.statements = statements;
        this.people = people;
        this.claims = claims;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** Each person's statement summary on the sheet, by person id; a person with none is missing. */
    public Map<Long, StatementSummary> summaries(String sheetId) {
        Map<Long, StatementSummary> by = new HashMap<>();
        for (Statement s : statements.findBySheetIdOrderBySeqAscIdAsc(sheetId)) {
            by.put(s.getPersonId(), by.getOrDefault(s.getPersonId(), StatementSummary.NONE).then(s));
        }
        return by;
    }

    public StatementSummary summary(String sheetId, Long personId) {
        StatementSummary summary = StatementSummary.NONE;
        for (Statement s : statements.findBySheetIdAndPersonIdOrderBySeqAscIdAsc(sheetId, personId)) {
            summary = summary.then(s);
        }
        return summary;
    }

    /**
     * Take a statement of the person on the sheet's today, or as of an earlier date ({@code asOf}, null: today);
     * refused for a date after the sheet's today, or before the person was added.
     */
    @Transactional
    public Person take(String sheetId, String user, Long personId, LocalDate asOf) {
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, personId));
        Sheet sheet = sheets.get(sheetId);
        LocalDate today = sheet.getToday();
        if (asOf == null) {
            return take(sheet, user, person);
        }
        if (asOf.isAfter(today)) {
            throw new StatementDateException(person.getName(), asOf, "it is after the sheet's today " + today);
        }
        if (!person.addedBy(asOf)) {
            throw new StatementDateException(person.getName(), asOf, "the person was not on the sheet then");
        }
        return takeAsOf(sheet, user, person, asOf);
    }

    private Person take(Sheet sheet, String user, Person person) {
        String sheetId = sheet.getId();
        Long personId = person.getId();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate today = sheet.getToday();
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId);
        // As on the screen: n/a when the grade in force today has no rate today, unless a counting claim has a fixed due
        // or a due on or before today.
        boolean anyDue = rates.rateOn(sheetId, person.gradeOn(today), today).isPresent();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int counted = 0;
        for (Claim claim : theirs) {
            if (!person.counts(claim, today)) {
                claim.setStated(false, null);
                continue;
            }
            counted++;
            if (claim.getSettledDue() != null || claim.getBoundDue() != null) {
                anyDue = true;
            }
            // The statement binds the claim's due as shown, late charge and floor included; a claim already bound
            // shows its bound due, so it stays as it is.
            BigDecimal figure = figureOf(sheetId, person, claim).orElse(null);
            BigDecimal due = claim.getSettledDue() != null || claim.isBound() || claim.isCorrection() ? figure
                    : sheet.floored(sheet.withLateCharge(figure, claim.getStatus().isOpen() && !claim.isWaived(),
                            sheet.overdueBy(claim.getDueBy(), person.gradeOn(claim.getDate()), claim.getDate()),
                            claim.getDate()), claim.getDate());
            if (due != null) {
                total = total.add(due);
                // A due under a grade the person had on an earlier date counts too.
                if (!claim.getDate().isAfter(today)) {
                    anyDue = true;
                }
            }
            claim.setStated(true, due);
        }
        claims.saveAllAndFlush(theirs);
        String dueTotal = anyDue ? sheet.capped(total).toPlainString() : "n/a";
        statements.saveAndFlush(new Statement(sheetId, personId, today, today, dueTotal, counted,
                nextSeq(sheetId, personId)));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + dueTotal + " claims=" + counted);
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /**
     * A statement as of an earlier date: the person's claims that counted at the end of it are bound to their figures
     * as they stood then, counting only the changes made while the sheet's today was on or before it, and with that
     * date standing in for the sheet's today in every figure; each is bound to its due as shown then, late charge
     * included. Every other claim of theirs is left unbound.
     */
    private Person takeAsOf(Sheet sheet, String user, Person person, LocalDate asOf) {
        String sheetId = sheet.getId();
        Long personId = person.getId();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> theirs = claims.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId);
        // The claims' bindings stood then only when their last statement was taken by then.
        List<Statement> taken = statements.findBySheetIdAndPersonIdOrderBySeqAscIdAsc(sheetId, personId);
        boolean bindingsStood = taken.isEmpty() || taken.get(taken.size() - 1).madeBy(asOf);
        boolean anyDue = rates.rateAsOf(sheetId, person.gradeOn(asOf), asOf, asOf).isPresent();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        int counted = 0;
        for (Claim claim : theirs) {
            ClaimStatus status = statusAsOf(claim, asOf);
            boolean bound = bindingsStood && claim.isStated();
            boolean counts = status != null && status.counts()
                    && !(status.isOpen() && !bound
                            && (person.heldAsOf(claim.getDate(), asOf) || person.retiredAsOf(asOf)))
                    && !(status == ClaimStatus.OPEN && claim.getDeferredUntil() != null
                            && asOf.isBefore(claim.getDeferredUntil()));
            if (!counts) {
                claim.setStated(false, null);
                continue;
            }
            counted++;
            BigDecimal settled = status == ClaimStatus.SETTLED ? claim.getSettledDue() : null;
            if (settled != null || (bound && claim.getStatedDue() != null)) {
                anyDue = true;
            }
            BigDecimal figure = settled != null ? settled
                    : bound ? claim.getStatedDue()
                    : rates.rateAsOf(sheetId, person.gradeOn(claim.getDate()), claim.getDate(), asOf, claim.rateStamp())
                            .map(pct -> sheet.rateStep(claim.getAmount(), pct, claim.getDate(), claim.getRoundingDate(), asOf))
                            .orElse(null);
            BigDecimal due = settled != null || bound || claim.isCorrection() ? figure
                    : sheet.floored(sheet.withLateCharge(figure, status.isOpen() && !claim.isWaived(),
                            sheet.overdueBy(claim.getDueBy(), person.gradeOn(claim.getDate()), claim.getDate()),
                            claim.getDate(), asOf), claim.getDate());
            if (due != null) {
                total = total.add(due);
                if (!claim.getDate().isAfter(asOf)) {
                    anyDue = true;
                }
            }
            claim.setStated(true, due);
        }
        claims.saveAllAndFlush(theirs);
        String dueTotal = anyDue ? sheet.capped(total, asOf).toPlainString() : "n/a";
        statements.saveAndFlush(new Statement(sheetId, personId, asOf, sheet.getToday(), dueTotal,
                counted, nextSeq(sheetId, personId)));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + dueTotal + " claims=" + counted + " asof=" + asOf);
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** The place of the person's next statement: after their last. */
    private long nextSeq(String sheetId, Long personId) {
        List<Statement> taken = statements.findBySheetIdAndPersonIdOrderBySeqAscIdAsc(sheetId, personId);
        return taken.isEmpty() ? 1 : taken.get(taken.size() - 1).getSeq() + 1;
    }

    /** The status the claim had at the end of the date: its last status logged on or before it; null with none. */
    private ClaimStatus statusAsOf(Claim claim, LocalDate asOf) {
        ClaimStatus status = null;
        for (ClaimLog log : claimLogs.findByClaimIdOrderByIdAsc(claim.getId())) {
            if (!log.getDate().isAfter(asOf)) {
                status = log.getStatus();
            }
        }
        return status;
    }

    /**
     * A steward cancels the person's last statement: it is removed, so the statement before it (if any) is the last,
     * and the claims it bound are live again; a figure an earlier statement bound them to is not restored. Refused
     * for anyone else, and for a person with no statement.
     */
    @Transactional
    public Person cancelLast(String sheetId, String user, Long personId) {
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, personId));
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new StatementCancelForbiddenException(sheetId, user);
        }
        List<Statement> theirs = statements.findBySheetIdAndPersonIdOrderBySeqAscIdAsc(sheetId, personId);
        if (theirs.isEmpty()) {
            throw new NoStatementException(sheetId, personId);
        }
        Statement last = theirs.get(theirs.size() - 1);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> bound = claims.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId);
        for (Claim claim : bound) {
            claim.setStated(false, null);
        }
        claims.saveAllAndFlush(bound);
        statements.delete(last);
        statements.flush();
        audit.recordOnCommit("STATEMENT_CANCELLED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + last.getDueTotal());
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /**
     * The figure under the claim's due: the due fixed when it was settled, else the due (late charge included) its
     * last statement bound it to, else its amount times its grade's rate on its date (no floor); empty when it has none (n/a).
     */
    private Optional<BigDecimal> figureOf(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return Optional.of(claim.getSettledDue());
        }
        if (claim.isBound()) {
            return Optional.ofNullable(claim.getStatedDue());
        }
        return rates.rateOn(sheetId, person.gradeOn(claim.getDate()), claim.getDate(), claim.rateStamp())
                .map(Rate::getPct)
                .map(pct -> sheets.get(sheetId).rateStep(claim.getAmount(), pct, claim.getDate(), claim.getRoundingDate()));
    }
}
