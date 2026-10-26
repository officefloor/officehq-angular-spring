package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claims people make on a sheet. */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateRepository rates;
    private final PeriodRepository periods;
    private final SheetService sheets;
    private final RollupService rollup;
    private final SettingsService settings;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateRepository rates,
            PeriodRepository periods, SheetService sheets, RollupService rollup, SettingsService settings,
            Audit audit) {
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.periods = periods;
        this.sheets = sheets;
        this.rollup = rollup;
        this.settings = settings;
        this.audit = audit;
    }

    /** All the claims on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /**
     * Adds a claim for the person; a missing date takes the sheet's today. Refused when dated in a closed period or
     * after the person's retirement date.
     */
    @Transactional
    public Claim add(String by, String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        LocalDate on = dateOrToday(sheetId, date);
        refuseInClosedPeriod(sheetId, on);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = person(sheetId, personId);
        refuseAfterRetirement(person, on);
        Claim added = claims.saveAndFlush(new Claim(sheetId, personId, scaled(amount), on, noteOf(note)));
        audit.recordOnCommit(record("CLAIM_ADDED", by, person, added));
        rollup.recordChanges(by, sheetId, before);
        return added;
    }

    /**
     * Edits a claim; refused unless it is a draft, when it is dated in a closed period, or when the edit would date
     * it in one. An open claim is changed by voiding it and adding a new one.
     */
    @Transactional
    public Claim update(String by, String sheetId, Long id, BigDecimal amount, LocalDate date, String note) {
        LocalDate on = dateOrToday(sheetId, date);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a draft claim can be edited.");
        }
        refuseInClosedPeriod(sheetId, claim.getDate());
        refuseInClosedPeriod(sheetId, on);
        refuseAfterRetirement(person(sheetId, claim.getPersonId()), on);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(scaled(amount));
        claim.setDate(on);
        claim.setNote(noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_EDITED", by, person(sheetId, saved.getPersonId()), saved));
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Moves the claim to the status; a change its status does not allow is refused, as is settling by the user
     * who opened it, settling by a clerk when the due is above the clerk limit, and any change to a claim dated in
     * a closed period.
     */
    @Transactional
    public Claim changeStatus(String by, String sheetId, Long id, ClaimStatus next) {
        // the sheet is locked before the claim, in the order a period closing takes them
        sheets.lock(sheetId);
        Claim claim = claims.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseInClosedPeriod(sheetId, claim.getDate());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        if (!claim.getStatus().canMoveTo(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        if (next == ClaimStatus.SETTLED && by.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The user who opened a claim cannot settle it.");
        }
        Long number = claim.getNumber();
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN) {
            number = sheets.nextClaimNumber(sheetId);
            claim.setNumber(number);
            claim.setOpenedBy(by);
        }
        Person person = person(sheetId, claim.getPersonId());
        String due = null;
        if (next == ClaimStatus.SETTLED) {
            // the due is fixed at the figure a statement bound it to, else at the rate the person's grade has in
            // force on the claim's date, with the late charge it has gained by today; with none it stays live
            BigDecimal figure = claim.isStated() && claim.getStatedDue() != null ? claim.getStatedDue()
                    : liveDue(sheetId, person, claim);
            BigDecimal fixed = withLateCharge(figure, claim.getDate(), sheets.load(sheetId));
            settings.refuseAboveClerkLimit(sheetId, by, fixed);
            claim.setSettledDue(fixed);
            due = fixed == null ? "n/a" : fixed.toPlainString();
        }
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        audit.recordOnCommit(record(event, by, person, saved, number) + (due != null ? " due=" + due : ""));
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * The claim's live due: at the rate its person's grade in force on its date has in force on that date, and at least the sheet's
     * floor; null (n/a) with none.
     */
    public BigDecimal liveDue(String sheetId, Person person, Claim claim) {
        BigDecimal floor = sheets.load(sheetId).getFloor();
        return rates.findInForce(sheetId, person.gradeOn(claim.getDate()), claim.getDate()).map(Rate::getPct)
                .map(pct -> floored(dueOn(claim.getAmount(), pct), floor)).orElse(null);
    }

    /** The figure the claim shows and totals: its settled due, else the one a statement bound it to, else live. */
    public BigDecimal figure(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isStated() ? claim.getStatedDue() : liveDue(sheetId, person, claim);
    }

    /** The due the claim shows and totals: its figure, with the late charge an open overdue claim has gained. */
    public BigDecimal due(String sheetId, Person person, Claim claim) {
        BigDecimal figure = figure(sheetId, person, claim);
        return claim.getStatus() == ClaimStatus.OPEN ? withLateCharge(figure, claim.getDate(), sheets.load(sheetId))
                : figure;
    }

    /**
     * The figure (null for n/a) of a claim dated on the date, with the sheet's late percent of it added for every
     * full 30 days it is overdue by the sheet's today, rounded like a due. The charge is never bound by a statement:
     * it keeps following the sheet's today.
     */
    public static BigDecimal withLateCharge(BigDecimal figure, LocalDate date, Sheet sheet) {
        if (figure == null || sheet == null || sheet.getToday() == null) {
            return figure;
        }
        long overdue = ChronoUnit.DAYS.between(date.plusDays(sheet.getTerms()), sheet.getToday());
        long periods = overdue > 0 ? overdue / 30 : 0;
        if (periods == 0 || sheet.getLatePct().signum() == 0) {
            return figure;
        }
        return figure.add(dueOn(figure, sheet.getLatePct().multiply(BigDecimal.valueOf(periods))));
    }

    /**
     * Refuses a change to a claim dated on the date when a closed period contains it. The sheet stays locked until
     * the transaction ends, so a period closing at the same moment is checked in turn.
     */
    private void refuseInClosedPeriod(String sheetId, LocalDate date) {
        sheets.lock(sheetId);
        if (periods.existsBySheetIdAndClosedTrueAndFromLessThanEqualAndToGreaterThanEqual(sheetId, date, date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That claim is dated in a closed period.");
        }
    }

    /** Refuses a claim for the person dated after their retirement date; one on or before it is allowed. */
    private static void refuseAfterRetirement(Person person, LocalDate date) {
        if (person.getRetiredFrom() != null && date.isAfter(person.getRetiredFrom())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "That claim is dated after the person's retirement.");
        }
    }

    private Person person(String sheetId, Long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
    }

    private static String record(String event, String by, Person person, Claim c) {
        return record(event, by, person, c, c.getNumber());
    }

    /** The audit line about a claim; one that has a number ends with it. */
    private static String record(String event, String by, Person person, Claim c, Long number) {
        return event + " sheet=" + c.getSheetId() + " by=" + by + " person=" + person.getName()
                + " amount=" + c.getAmount().toPlainString() + " date=" + c.getDate()
                + (number != null ? " number=" + number : "");
    }

    /** The due on an amount at a rate: amount times rate divided by 100, rounded half to even to two decimals. */
    public static BigDecimal dueOn(BigDecimal amount, BigDecimal pct) {
        return amount.multiply(pct).movePointLeft(2).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** The due raised to the floor when it is below it. */
    public static BigDecimal floored(BigDecimal due, BigDecimal floor) {
        return floor != null && due.compareTo(floor) < 0 ? floor.setScale(2) : due;
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.load(sheetId).getToday();
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.strip();
    }
}
