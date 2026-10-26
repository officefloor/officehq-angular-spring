package net.officefloor.hq.app.statement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimBinding;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.ClaimStatus;
import net.officefloor.hq.app.claim.LateCharge;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.setting.DatedSettings;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StatementService {

    /** A person's statements: how many were taken, and the last one (none when none was). */
    public record Summary(long personId, int count, Statement last) {
    }

    /** A statement just taken: the person's summary after it, and their claims as it bound them, newest first. */
    public record Taken(Summary summary, List<Claim> claims) {
    }

    private final StatementRepository statements;
    private final PersonRepository people;
    private final ClaimRepository claims;
    private final ClaimService claimService;
    private final RateService rates;
    private final SheetService sheets;
    private final Rollup rollup;
    private final SettingService settings;
    private final Audit audit;

    public StatementService(StatementRepository statements, PersonRepository people, ClaimRepository claims,
            ClaimService claimService, RateService rates, SheetService sheets, Rollup rollup, SettingService settings,
            Audit audit) {
        this.statements = statements;
        this.people = people;
        this.claims = claims;
        this.claimService = claimService;
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.settings = settings;
        this.audit = audit;
    }

    /** The statement summary of each person on the sheet who has had a statement taken. */
    @Transactional(readOnly = true)
    public List<Summary> list(String sheetId) {
        Map<Long, Summary> by = new LinkedHashMap<>();
        for (Statement s : statements.findBySheetIdOrderByIdAsc(sheetId)) {
            Summary was = by.get(s.getPersonId());
            by.put(s.getPersonId(), was == null ? new Summary(s.getPersonId(), 1, s)
                    : new Summary(s.getPersonId(), was.count() + 1, later(was.last(), s)));
        }
        return List.copyOf(by.values());
    }

    /** A person's summary from their statements in the order they were taken; none when they have none. */
    private static Summary summarise(long personId, List<Statement> all) {
        Statement last = null;
        for (Statement s : all) last = last == null ? s : later(last, s);
        return last == null ? null : new Summary(personId, all.size(), last);
    }

    /** Of a person's last statement so far and one taken after it, which is now their last: their own
     *  rather than one carried to them from another. */
    private static Statement later(Statement last, Statement next) {
        return next.isCarried() && !last.isCarried() ? last : next;
    }

    /** Take the person's statement: record their due-total now, bind each of their counting claims to
     *  the figure it shows now, and leave every other claim of theirs unbound. Taken as of a date (asOf),
     *  it does so with their claims as they stood at the end of that date: every change made while the
     *  sheet's today was on or before it counts, every later one does not, and the date stands in for the
     *  sheet's today in every figure. Refused for a date after the sheet's today, and one before the
     *  person was added. */
    @Transactional
    public Taken take(String sheetId, String user, long personId, LocalDate asOf) {
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        LocalDate today = sheets.today(sheetId);
        if (asOf != null && asOf.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A statement cannot be taken as of a date after the sheet's today.");
        }
        if (asOf != null && person.getAddedOn() != null && asOf.isBefore(person.getAddedOn())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " was not yet on the sheet on " + asOf + ".");
        }
        LocalDate on = asOf != null ? asOf : today;
        DatedSettings dated = asOf != null ? settings.datedAsOf(sheetId, asOf) : settings.dated(sheetId);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> theirs = claims.findForStatement(sheetId, personId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean keptFigure = false;
        int counting = 0;
        for (Claim claim : theirs) {
            Stood stood = asOf != null ? stoodAsOf(sheetId, person, claim, asOf, dated) : stoodNow(sheetId, person, claim, today);
            if (stood == null) {
                claim.unbind(today);
                continue;
            }
            counting++;
            if (stood.due() != null) total = total.add(stood.due());
            if (stood.keptFigure()) keptFigure = true;
            claim.bind(stood.figure(), today);
        }
        // As the screen shows it: n/a when no rate of the grade in force on the statement's date is in force
        // then and no claim keeps a figure; held to the sheet's cap.
        Grade grade = person.gradeOn(on, asOf);
        boolean noRate = (asOf != null ? rates.inForceAsOf(sheetId, grade, on, asOf) : rates.inForce(sheetId, grade, on)) == null;
        BigDecimal dueTotal = SettingService.capped(noRate && !keptFigure ? null : total, dated.capOn(on));
        claims.saveAllAndFlush(theirs);
        statements.saveAndFlush(new Statement(sheetId, personId, dueTotal, counting, today, on, user));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + counting
                + (asOf == null ? "" : " asof=" + asOf));
        rollup.recordChanges(user, before);
        return new Taken(summarise(personId, statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId)),
                theirs);
    }

    /** How a counting claim stood for a statement: the figure it binds (before late charge and floor; null:
     *  n/a), its due, and whether it keeps a figure should the grade's rate be n/a. */
    private record Stood(BigDecimal figure, BigDecimal due, boolean keptFigure) {
    }

    /** How the claim stands now; null when it does not count. */
    private Stood stoodNow(String sheetId, Person person, Claim claim, LocalDate today) {
        if (!counts(person, claim, today)) return null;
        // The statement binds the rate figure, before late charge and floor; the due-total it records is the due.
        BigDecimal figure = claimService.figure(sheetId, person, claim);
        BigDecimal due = claimService.due(sheetId, person, claim);
        boolean kept = claim.getSettledDue() != null || (claim.isBound() && claim.getStatedDue() != null)
                // A live figure at a grade other than today's is not undone by today's grade having no rate.
                || (figure != null && person.gradeOn(claim.getDate()) != person.gradeOn(today))
                // Nor is a live figure on a claim dated by today undone by its rate having ended since.
                || (figure != null && !claim.getDate().isAfter(today));
        return new Stood(figure, due, kept);
    }

    /** How the claim stood at the end of the date (asOf), which stands in for the sheet's today: its status,
     *  settled due and binding then, the grade, rate, floor, late percent and holds and retirement in force
     *  as they stood then; null when it did not count then (or was not yet added). */
    private Stood stoodAsOf(String sheetId, Person person, Claim claim, LocalDate asOf, DatedSettings dated) {
        ClaimStatus status = claim.statusAsOf(asOf);
        if (status == null) return null;
        BigDecimal settled = status == ClaimStatus.SETTLED ? claim.settledDueAsOf(asOf) : null;
        ClaimBinding binding = status == ClaimStatus.PENDING ? null : claim.bindingAsOf(asOf);
        boolean deferred = status == ClaimStatus.OPEN && claim.getDeferredUntil() != null
                && asOf.isBefore(claim.getDeferredUntil());
        boolean counts = status == ClaimStatus.SETTLED || (status.isOpen() && !deferred
                && (binding != null || !person.isLeftOutAsOf(claim.getDate(), asOf)));
        if (!counts) return null;
        RoundingMode rounding = claimService.rounding(sheetId, claim, asOf, dated);
        Grade grade = person.gradeOn(claim.getDate(), asOf);
        BigDecimal figure;
        if (settled != null) {
            figure = settled;
        } else if (binding != null) {
            figure = binding.getStatedDue();
        } else {
            Rate rate = rates.inForceAsOf(sheetId, grade, claim.getDate(), asOf, claim.getId());
            figure = rate == null ? null
                    : claim.getAmount().multiply(rate.getPct()).divide(BigDecimal.valueOf(100), 2, rounding);
        }
        BigDecimal floor = dated.floorOn(claim.getDate());
        BigDecimal due;
        if (figure == null || settled != null) {
            due = figure;
        } else if (status.isOpen() && !deferred) {
            due = LateCharge.dueAsOpen(claim, figure, floor, asOf, dated.graceOn(grade, claim.getDate()),
                    dated::latePctOn, rounding);
        } else {
            due = LateCharge.floored(claim, figure, floor);
        }
        boolean kept = settled != null || (binding != null && binding.getStatedDue() != null)
                || (figure != null && grade != person.gradeOn(asOf, asOf))
                || (figure != null && !claim.getDate().isAfter(asOf));
        return new Stood(figure, due, kept);
    }

    /** A statement just cancelled: the person's summary after it (none when no statement is left), and
     *  their claims, live again, newest first. */
    public record Cancelled(Summary summary, List<Claim> claims) {
    }

    /** Cancel the person's last statement: the claims it bound are live again (a figure an earlier
     *  statement bound is not restored) and the statement before it is their last (one carried to them
     *  only when none of their own is left). Refused unless the
     *  user is a steward of the sheet, or when the person has no statement. */
    @Transactional
    public Cancelled cancel(String sheetId, String user, long personId) {
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward cancels a statement.");
        }
        List<Statement> all = statements.findBySheetIdAndPersonIdOrderByIdAsc(sheetId, personId);
        if (all.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, person.getName() + " has no statement to cancel.");
        }
        Statement last = summarise(personId, all).last();
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> theirs = claims.findForStatement(sheetId, personId);
        // Only the last statement binds claims, so every claim it bound is unbound.
        LocalDate today = sheets.today(sheetId);
        theirs.forEach((claim) -> claim.unbind(today));
        claims.saveAllAndFlush(theirs);
        statements.delete(last);
        statements.flush();
        audit.recordOnCommit("STATEMENT_CANCELLED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (last.getDueTotal() == null ? "n/a" : last.getDueTotal().toPlainString()));
        rollup.recordChanges(user, before);
        List<Statement> left = all.stream().filter((s) -> s != last).toList();
        return new Cancelled(summarise(personId, left), theirs);
    }

    /** Only open and settled claims count toward what is claimed and due; a person's open claims dated
     *  within one of their holds, or all of them once their retirement date is reached, do not, unless a
     *  statement binds them, nor does a claim deferred on the
     *  sheet's today, as on the screen. */
    private static boolean counts(Person person, Claim claim, LocalDate today) {
        return claim.getStatus() == ClaimStatus.SETTLED
                || (claim.getStatus().isOpen() && !claim.isDeferredOn(today)
                        && (claim.isBound() || !person.isLeftOut(claim.getDate(), today)));
    }
}
