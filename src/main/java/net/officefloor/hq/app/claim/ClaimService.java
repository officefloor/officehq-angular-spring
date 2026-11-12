package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.person.Statement;
import net.officefloor.hq.app.person.StatementRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the people's claims on each sheet. A claim with no date is dated the sheet's today. */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateRepository rates;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final PeriodRepository periods;
    private final StatementRepository statements;
    private final Rollup rollup;
    private final RoleService roles;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateRepository rates, SheetService sheets,
            SheetRepository sheetRows, PeriodRepository periods, StatementRepository statements, Rollup rollup,
            RoleService roles, Audit audit) {
        this.roles = roles;
        this.sheetRows = sheetRows;
        this.periods = periods;
        this.rollup = rollup;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.statements = statements;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        sheets.today(sheetId);
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim; a negative amount (a correction) must name a SETTLED claim of the person by number,
     *  and a claim of 0.00 or more must name none. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            Integer corrects) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, on);
        refuseRetired(person, on);
        refuseCorrection(sheetId, personId, amount, corrects);
        Rollup.Snapshot before = rollup.take(sheetId);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount.setScale(2), on, note, corrects));
        audit("CLAIM_ADDED", claim, person, user);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Change a DRAFT claim; a claim in any other status is refused, and a correction as when added. */
    @Transactional
    public Claim update(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            Integer corrects) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotDraftException(claim.getStatus());
        }
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDate());
        refuseClosed(sheetId, on);
        refuseRetired(people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new), on);
        refuseCorrection(sheetId, claim.getPersonId(), amount, corrects);
        Rollup.Snapshot before = rollup.take(sheetId);
        claim.setAmount(amount.setScale(2));
        claim.setDate(on);
        claim.setNote(note);
        claim.setCorrects(corrects);
        Claim updated = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(updated.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        audit("CLAIM_EDITED", updated, person, user);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Move a claim to another status; refused when its current status does not allow it,
     *  or when the user settling it is the one who opened it, or is a clerk and its due is above the
     *  sheet's clerk limit (a due of n/a is within any limit). Settling fixes the
     *  claim's due at its grade's rate in force on its date (a claim bound by a statement at the figure it
     *  bound), its late charge on the sheet's today frozen in on top; with no figure then, its due stays live. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus to) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(to)) {
            throw new ClaimStatusException(claim.getStatus(), to);
        }
        if (to == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ClaimSettlerException();
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        Integer number = claim.getNumber();
        if (to == ClaimStatus.OPEN) {
            number = sheets.nextClaimNumber(sheetId);
            claim.setOpenedBy(user);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        if (to == ClaimStatus.SETTLED) {
            BigDecimal figure = claim.isStated() ? claim.getStatedDue() : liveDue(claim, person);
            BigDecimal due = figure == null ? null : charged(claim, figure);
            refuseOverLimit(sheetId, user, due);
            claim.setSettledDue(due);
        }
        claim.setStatus(to);
        claim.setNumber(number);
        Claim updated = claims.saveAndFlush(claim);
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        audit(event, updated, person, user, number, to == ClaimStatus.SETTLED
                ? " due=" + (updated.getSettledDue() == null ? "n/a" : updated.getSettledDue().toPlainString())
                : "");
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** A person, their statements in the order taken, and their claims newest first. */
    public record Stated(Person person, List<Statement> statements, List<Claim> claims) {
    }

    /** Take a person's statement: record their due-total now (late charges included) and how many claims
     *  count, and bind each claim that counts to the figure it has now, under its late charge (the charge is
     *  never bound: it keeps following the sheet's today); a claim that does not count, or is OPEN while
     *  its person is on hold, is left unbound. */
    @Transactional
    public Stated statement(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate today = sheets.today(sheetId);
        List<Claim> theirs = claims.findForUpdateBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
        Rollup.Snapshot before = rollup.take(sheetId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean figure = false;
        int counted = 0;
        for (Claim claim : theirs) {
            if (!claim.getStatus().counts() || (person.isHeld() && claim.getStatus() == ClaimStatus.OPEN)) {
                claim.setStated(false, null);
                continue;
            }
            BigDecimal due = due(claim, person);
            if (due != null) {
                total = total.add(charged(claim, due));
                figure = true;
            }
            claim.setStated(true, due);
            counted++;
        }
        boolean rated = rates.findInForce(sheetId, person.gradeOn(today), today).isPresent();
        BigDecimal dueTotal = rated || figure ? total : null;
        claims.saveAllAndFlush(theirs);
        statements.saveAndFlush(new Statement(sheetId, personId, today, dueTotal, counted));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + counted);
        rollup.recordChanges(sheetId, user, before);
        return new Stated(person, statements.findByPersonIdOrderByIdAsc(personId), theirs);
    }

    /** Refuse a change to a claim dated inside a closed period. Locks the sheet first, so a period being
     *  closed at the same moment is seen. */
    private void refuseClosed(String sheetId, LocalDate date) {
        sheetRows.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (periods.closedOn(sheetId, date)) {
            throw new PeriodClosedException();
        }
    }

    /** Refuse a clerk settling a due above the sheet's clerk limit; a steward settles anything, and a due
     *  of n/a, or a sheet with no limit, is within it. */
    private void refuseOverLimit(String sheetId, String user, BigDecimal due) {
        BigDecimal limit = sheetRows.findById(sheetId).orElseThrow(SheetNotFoundException::new).getClerkLimit();
        if (limit != null && due != null && due.compareTo(limit) > 0 && !roles.isSteward(sheetId, user)) {
            throw new ClaimLimitException();
        }
    }

    /** Refuse a negative amount that does not name a SETTLED claim of the person by number, and a claim of
     *  0.00 or more that names one. */
    private void refuseCorrection(String sheetId, long personId, BigDecimal amount, Integer corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new ClaimCorrectionException("Only a negative amount corrects a claim.");
            }
            return;
        }
        if (corrects == null) {
            throw new ClaimCorrectionException(
                    "A negative amount is a correction: name the number of the settled claim it corrects.");
        }
        if (!claims.existsBySheetIdAndPersonIdAndNumberAndStatus(sheetId, personId, corrects, ClaimStatus.SETTLED)) {
            throw new ClaimCorrectionException("A correction must name a settled claim of the same person.");
        }
    }

    /** Refuse a claim dated after its person's retirement date. */
    private static void refuseRetired(Person person, LocalDate date) {
        if (person.retiredOn(date)) {
            throw new PersonRetiredException();
        }
    }

    /** A claim's figure: the due fixed when settled, else the one its last statement bound, else its live
     *  due; none when that is n/a. */
    private BigDecimal due(Claim claim, Person person) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isStated() ? claim.getStatedDue() : liveDue(claim, person);
    }

    /** A claim's figure with its late charge on the sheet's today on top while it is OPEN. */
    private BigDecimal charged(Claim claim, BigDecimal figure) {
        return Rollup.charged(sheetRows.findById(claim.getSheetId()), claim, figure);
    }

    /** The claim's amount at the rate in force on its date of its person's grade in force then in force on its date, at least the sheet's floor
     *  (a correction has none); none when no rate applies. */
    private BigDecimal liveDue(Claim claim, Person person) {
        Sheet sheet = sheetRows.findById(claim.getSheetId()).orElseThrow(SheetNotFoundException::new);
        RoundingMode rounding = sheet.rounding(roundingDate(claim));
        return rates.findInForce(claim.getSheetId(), person.gradeOn(claim.getDate()), claim.getDate())
                .map(rate -> claim.isCorrection() ? due(claim.getAmount(), rate, rounding)
                        : sheet.floored(due(claim.getAmount(), rate, rounding)))
                .orElse(null);
    }

    /** The date a claim's figures round by: its own, or for a correction the date of the claim it corrects. */
    private LocalDate roundingDate(Claim claim) {
        if (!claim.isCorrection()) {
            return claim.getDate();
        }
        return claims.findBySheetIdAndPersonIdAndNumber(claim.getSheetId(), claim.getPersonId(), claim.getCorrects())
                .map(Claim::getDate).orElse(claim.getDate());
    }

    /** An amount times a rate, divided by 100, rounded to cents. */
    private static BigDecimal due(BigDecimal amount, Rate rate, RoundingMode rounding) {
        return amount.multiply(rate.getRate()).movePointLeft(2).setScale(2, rounding);
    }

    /** The amount as the screen shows it (two decimals), the date YYYY-MM-DD. */
    private void audit(String event, Claim claim, Person person, String user) {
        audit(event, claim, person, user, claim.getNumber(), "");
    }

    /** A claim with a number (a voided claim keeps its own) follows its date with number=, then any extra fields. */
    private void audit(String event, Claim claim, Person person, String user, Integer number, String extra) {
        audit.recordOnCommit(event + " sheet=" + claim.getSheetId() + " by=" + user
                + " person=" + person.getName() + " amount=" + claim.getAmount().toPlainString()
                + " date=" + claim.getDate() + (number != null ? " number=" + number : "") + extra);
    }
}
