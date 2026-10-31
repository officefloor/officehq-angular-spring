package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claims people on a sheet have made. */
@Service
public class ClaimService {

    /** The prefix of the numbers given within no period. */
    private static final String NO_PERIOD = "X";

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final RateRepository rates;
    private final Audit audit;
    private final Rollup rollup;
    private final PeriodRepository periods;
    private final RoleService roles;
    private final ClaimSequenceRepository sequences;

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets,
            SheetRepository sheetStore, RateRepository rates, Audit audit, Rollup rollup, PeriodRepository periods,
            RoleService roles, ClaimSequenceRepository sequences) {
        this.periods = periods;
        this.sequences = sequences;
        this.roles = roles;
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.rates = rates;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** Every claim on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** The ids of those given that are claims on the sheet, in list order: people in the order they are listed,
     *  each person's claims newest first. */
    @Transactional(readOnly = true)
    public List<Long> inListOrder(String sheetId, Collection<Long> ids) {
        Set<Long> wanted = new HashSet<>(ids);
        return claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter(c -> wanted.contains(c.getId()))
                .sorted(Comparator.comparing(Claim::getPersonId))
                .map(Claim::getId)
                .toList();
    }

    /** Add a claim for a person; a missing date takes the sheet's today, a missing note is empty. Refused
     *  when its date is inside a closed period, or when it is a correction that does not name a SETTLED claim of
     *  the person. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        sheets.load(sheetId, user);
        LocalDate today = sheetStore.findLockedById(sheetId).orElseThrow().getToday();
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        refuseIfClosed(sheetId, date != null ? date : today);
        refuseIfRetired(person, date != null ? date : today);
        refuseUnlessCorrects(sheetId, personId, amount, corrects);
        Rollup.Figures before = rollup.of(sheetId);
        Claim added = claims.saveAndFlush(
                new Claim(sheetId, personId, scaled(amount), date != null ? date : today, noteOf(note), corrects));
        audit.recordOnCommit(entry("CLAIM_ADDED", user, person, added, added.getNumber()));
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    /** Change a claim's amount, date, note and the claim it corrects; a missing date takes the sheet's today. Only
     *  a draft can be changed: to change an open claim, void it and add a new one. Refused when its date, as it is
     *  or as it would become, is inside a closed period, or when it would be a correction that does not name a
     *  SETTLED claim of its person. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        sheets.load(sheetId, user);
        LocalDate today = sheetStore.findLockedById(sheetId).orElseThrow().getToday();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited; void it and add a new one");
        }
        refuseIfClosed(sheetId, claim.getDate());
        refuseIfClosed(sheetId, date != null ? date : today);
        refuseIfRetired(people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(), date != null ? date : today);
        refuseUnlessCorrects(sheetId, claim.getPersonId(), amount, corrects);
        Rollup.Figures before = rollup.of(sheetId);
        claim.setAmount(scaled(amount));
        claim.setCorrects(corrects);
        claim.setDate(date != null ? date : today);
        claim.setNote(noteOf(note));
        Claim edited = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(edited.getPersonId(), sheetId).orElseThrow();
        audit.recordOnCommit(entry("CLAIM_EDITED", user, person, edited, edited.getNumber()));
        rollup.recordChanges(sheetId, user, before);
        return edited;
    }

    /**
     * Move a claim to a new status; a move its current status does not allow is refused. Opening a
     * claim gives it its next number (the sheet's next, or the next in its period while the sheet numbers per
     * period) and records who opened it; voiding it keeps its number, which is
     * never given again; settling it fixes its due: the figure a statement binds it to, else its amount
     * at the rate in force on its date of its person's grade in force on that date, as rates are now, with the late charge it has gained
     * added on top, so later rate changes leave it as it is. The user
     * who opened a claim cannot settle it, and a clerk settling it when that due is above the sheet's clerk limit
     * makes it PENDING instead, for a steward to approve or reject (a claim with no due is within any limit); a
     * steward settles anything. A claim dated inside a closed period cannot move. The sheet is locked
     * first, so moves on one sheet are made one at a time and no number is given twice.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        refuseIfClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next);
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot settle it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        BigDecimal due = dueNow(sheet, claim, person);
        if (next == ClaimStatus.SETTLED && overClerkLimit(sheet, user, due)) {
            next = ClaimStatus.PENDING;
        }
        Rollup.Figures before = rollup.of(sheetId);
        String number = claim.getNumber();
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN) {
            number = nextNumber(sheet, claim.getDate());
            claim.setNumber(number);
            claim.setOpenedBy(user);
        } else if (next == ClaimStatus.SETTLED) {
            claim.setSettledDue(due);
        }
        Claim changed = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        String line = entry(event, user, person, changed, number);
        if (next == ClaimStatus.SETTLED) {
            line += " due=" + dueText(changed);
        }
        audit.recordOnCommit(line);
        rollup.recordChanges(sheetId, user, before);
        return changed;
    }

    /** A steward approves a PENDING claim: it becomes SETTLED, its due fixed as it is at this moment (its live
     *  figure with the late charge it has gained). Refused for a clerk, for the user who opened the claim, for a
     *  claim that is not PENDING and for one dated inside a closed period. */
    @Transactional
    public Claim approve(String sheetId, String user, long id) {
        sheets.load(sheetId, user);
        Sheet sheet = sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = pendingClaim(sheetId, user, id);
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot approve it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        BigDecimal due = dueNow(sheet, claim, person);
        Rollup.Figures before = rollup.of(sheetId);
        claim.setStatus(ClaimStatus.SETTLED);
        claim.setSettledDue(due);
        Claim changed = claims.saveAndFlush(claim);
        audit.recordOnCommit(entry("CLAIM_APPROVED", user, person, changed, changed.getNumber())
                + " due=" + dueText(changed));
        rollup.recordChanges(sheetId, user, before);
        return changed;
    }

    /** A steward rejects a PENDING claim with a reason: it is OPEN again, bound again by its person's last
     *  statement if that statement included it. Refused for a clerk, for a claim that is not PENDING and for one
     *  dated inside a closed period. */
    @Transactional
    public Claim reject(String sheetId, String user, long id, String reason) {
        sheets.load(sheetId, user);
        sheetStore.findLockedById(sheetId).orElseThrow();
        Claim claim = pendingClaim(sheetId, user, id);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow();
        Rollup.Figures before = rollup.of(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        Claim changed = claims.saveAndFlush(claim);
        audit.recordOnCommit(entry("CLAIM_REJECTED", user, person, changed, changed.getNumber())
                + " reason=" + reason.trim());
        rollup.recordChanges(sheetId, user, before);
        return changed;
    }

    /** The PENDING claim a steward approves or rejects; refused for a clerk, a claim in any other status and one
     *  dated inside a closed period. */
    private Claim pendingClaim(String sheetId, String user, long id) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        refuseIfClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim is not waiting for approval");
        }
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward approves or rejects a claim");
        }
        return claim;
    }

    /** The due a claim would be settled at now: its bound figure, else its live one, with the late charge it has
     *  gained on top; null when it has no figure. */
    private BigDecimal dueNow(Sheet sheet, Claim claim, Person person) {
        String sheetId = sheet.getId();
        BigDecimal live = Rate.inForceOn(rates.findBySheetIdAndGrade(sheetId, person.gradeOn(claim.getDate())), claim.getDate())
                .map(Rate::getRate)
                .map(rate -> liveFigure(claim, rate, sheet.getFloor(), sheet.roundingFor(roundingDate(claim,
                        claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, claim.getPersonId())))))
                .orElse(null);
        // Settling freezes the claim's figure (its bound one, else its live one) with the late charge it has gained.
        BigDecimal figure = claim.getBoundDue() != null ? claim.getBoundDue() : live;
        return figure == null ? null : figure.add(lateCharge(claim, figure, sheet));
    }

    private static String dueText(Claim claim) {
        return claim.getSettledDue() == null ? "n/a" : claim.getSettledDue().toPlainString();
    }

    /** The next number for a claim dated on this day, opened now: within its period (<period name>-<n>, or X-<n>
     *  in no period) while the sheet's period numbering is on by its today, else the sheet's next number. */
    private String nextNumber(Sheet sheet, LocalDate date) {
        if (!sheet.numbersPerPeriod()) {
            return String.valueOf(sheet.nextClaimNumber());
        }
        String prefix = periods.findBySheetIdOrderByFromDateAsc(sheet.getId()).stream()
                .filter(p -> p.contains(date)).map(Period::getName).findFirst().orElse(NO_PERIOD);
        ClaimSequence sequence = sequences.findBySheetIdAndPrefix(sheet.getId(), prefix)
                .orElseGet(() -> new ClaimSequence(sheet.getId(), prefix));
        String number = sequence.next();
        sequences.save(sequence);
        return number;
    }

    /** Whether a settle by this user must wait for a steward: they are a clerk and the due is above the sheet's
     *  clerk limit (a claim with no due is within any limit). */
    private boolean overClerkLimit(Sheet sheet, String user, BigDecimal due) {
        BigDecimal limit = sheet.getClerkLimit();
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return false;
        }
        return !Role.STEWARD.equals(roles.roleOf(sheet.getId(), user));
    }

    /** Refuse a correction (a negative amount) that does not name, by number, a SETTLED claim of the same person,
     *  and a claim that is not a correction but names one. */
    private void refuseUnlessCorrects(String sheetId, long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Only a correction (a negative amount) names a claim it corrects");
            }
            return;
        }
        boolean settled = corrects != null && claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId).stream()
                .anyMatch(c -> corrects.equals(c.getNumber()) && c.getStatus() == ClaimStatus.SETTLED);
        if (!settled) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A correction must name the number of a SETTLED claim of the same person");
        }
    }

    /** Refuse a change to a claim dated inside a closed period of the sheet. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        if (periods.existsBySheetIdAndClosedTrueAndFromDateLessThanEqualAndToDateGreaterThanEqual(sheetId, date, date)) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "That claim's date is inside a closed period");
        }
    }

    /** Refuse a claim dated after its person's retirement date. */
    private static void refuseIfRetired(Person person, LocalDate date) {
        if (person.refusesClaimOn(date)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "That claim is dated after " + person.getName() + " retired");
        }
    }

    /** An audit line about a claim; one with a number (a void claim keeps the one it had) ends with it. */
    private static String entry(String event, String user, Person person, Claim claim, String number) {
        return event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number == null ? "" : " number=" + number);
    }

    /** The due on an amount at a rate (a percent): amount × rate / 100, rounded to the cent as the claim it is on
     *  rounds (half up rounds a negative half away from zero, as its opposite would). */
    public static BigDecimal dueOn(BigDecimal amount, BigDecimal rate, RoundingMode rounding) {
        return amount.multiply(rate).movePointLeft(2).setScale(2, rounding);
    }

    /** The date that decides how a claim's live figures round: its own date, or for a correction the date of the
     *  claim of the same person it corrects. */
    public static LocalDate roundingDate(Claim claim, Collection<Claim> sheetClaims) {
        if (claim.getCorrects() == null) {
            return claim.getDate();
        }
        return sheetClaims.stream()
                .filter(c -> c.getPersonId().equals(claim.getPersonId()) && claim.getCorrects().equals(c.getNumber()))
                .map(Claim::getDate).findFirst().orElse(claim.getDate());
    }

    /** The late charge an OPEN or PENDING claim has gained on its figure by the sheet's today: the sheet's late percent of
     *  the figure for every full 30 days past the day it is due by, rounded as the claim rounds; 0.00 when not overdue,
     *  it has no figure, or it is a correction. */
    public static BigDecimal lateCharge(Claim claim, BigDecimal figure, Sheet sheet) {
        BigDecimal none = BigDecimal.ZERO.setScale(2);
        if (figure == null || !claim.getStatus().isOpen() || claim.isCorrection()) {
            return none;
        }
        long periods = ChronoUnit.DAYS.between(claim.getDate().plusDays(sheet.getTerms()), sheet.getToday()) / 30;
        return periods <= 0 ? none : dueOn(figure, sheet.getLatePct().multiply(BigDecimal.valueOf(periods)),
                sheet.roundingFor(claim.getDate()));
    }

    /** A claim's live figure at a rate: its due on its amount, rounded as the claim rounds, raised to the sheet's
     *  floor unless it is a correction. */
    public static BigDecimal liveFigure(Claim claim, BigDecimal rate, BigDecimal floor, RoundingMode rounding) {
        BigDecimal due = dueOn(claim.getAmount(), rate, rounding);
        return claim.isCorrection() ? due : floored(due, floor);
    }

    /** A live due raised to the sheet's floor when it is below it. */
    public static BigDecimal floored(BigDecimal due, BigDecimal floor) {
        return floor != null && due.compareTo(floor) < 0 ? floor : due;
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
