package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The claims on a sheet: listed newest first, added to a person and edited one at a time. A claim
 * given no date takes the sheet's today. Its due-by is fixed when it is added: its date plus the terms of its
 * person's grade in force on that date (else the sheet's terms); it does not move when its date or the
 * terms change later. A claim is added as DRAFT and moves through its statuses
 * as {@link ClaimStatus} allows; a change it does not allow is refused. Opening a claim gives it
 * the sheet's next number (or, while the sheet numbers by period, the next within its period), which it
 * keeps when voided and is never given again. Only a DRAFT claim
 * can be edited; an OPEN one is changed by voiding it and adding a new one. Settling it
 * fixes its due at its figure (the due bound by a statement, else its person's grade rate of the
 * moment on the claim's date) with its late charge of the moment frozen in; with no figure it
 * stays live. A steward can re-settle a SETTLED claim at the figure it would have today as an open claim
 * bound by no statement. The user who opened a claim cannot settle it, and a clerk cannot settle one whose
 * due is above the sheet's clerk limit (a steward can). No claim dated inside a closed period can
 * be added, edited (to or from such a date), opened, settled, re-settled or voided. No claim can be added
 * or edited to a date after its person's retirement date. A claim with a negative amount is a
 * correction: it must name, by number, a SETTLED claim of the same person; only a correction may name one.
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateService rates;
    private final SheetService sheets;
    private final RollupService rollup;
    private final PeriodRepository periods;
    private final SettingService settings;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateService rates, SheetService sheets,
            RollupService rollup, PeriodRepository periods, SettingService settings, Audit audit) {
        this.settings = settings;
        this.periods = periods;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        // The snapshot locks the sheet first, so the person is read as retired by any retirement before.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate on = dateOrToday(sheetId, date);
        refuseIfClosed(sheetId, on);
        if (!person.mayClaimOn(on)) throw new PersonRetiredException();
        refuseUnlessCorrects(sheetId, personId, amount, corrects);
        LocalDate dueBy = on.plusDays(settings.termsFor(sheetId, person.gradeOn(on)));
        Claim draft = new Claim(sheetId, personId, amount, on, note, dueBy);
        draft.setCorrects(corrects);
        Claim claim = claims.saveAndFlush(draft);
        logStatus(sheetId, user, person, claim);
        claim = claims.saveAndFlush(claim);
        record("CLAIM_ADDED", sheetId, user, person, claim);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    @Transactional
    public Claim edit(String sheetId, String user, Long id, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimStatusRefusedException();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate on = dateOrToday(sheetId, date);
        refuseIfClosed(sheetId, claim.getDate());
        refuseIfClosed(sheetId, on);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        if (!person.mayClaimOn(on)) throw new PersonRetiredException();
        refuseUnlessCorrects(sheetId, person.getId(), amount, corrects);
        claim.setAmount(amount);
        claim.setCorrects(corrects);
        claim.setDate(on);
        claim.setNote(note);
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_EDITED", sheetId, user, person, saved);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    @Transactional
    public Claim changeStatus(String sheetId, String user, Long id, ClaimStatus next) {
        // Opening takes the sheet's next number, locking the sheet first so the claim is read only
        // once no other open on the sheet is under way; a refusal rolls the number back.
        Sheet numbering = next == ClaimStatus.OPEN ? sheets.lockForNumbering(sheetId) : null;
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (!claim.getStatus().canBecome(next)) {
            throw new ClaimStatusRefusedException();
        }
        if (next == ClaimStatus.SETTLED && user.strip().equals(claim.getOpenedBy())) {
            throw new ClaimSettledByOpenerException();
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        // Settling freezes the late charge of the moment into the settled due.
        BigDecimal settling = next == ClaimStatus.SETTLED ? chargedFigureOf(sheetId, person, claim) : null;
        if (next == ClaimStatus.SETTLED && overClerkLimit(sheetId, user, settling)) next = ClaimStatus.PENDING;
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        refuseIfClosed(sheetId, claim.getDate());
        switch (next) {
            case OPEN -> {
                number(numbering, claim);
                claim.setOpenedBy(user.strip());
            }
            case SETTLED -> claim.setSettledDue(settling);
            default -> {}
        }
        claim.setStatus(next);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        String due = next == ClaimStatus.SETTLED
                ? " due=" + (saved.getSettledDue() == null ? "n/a" : saved.getSettledDue().setScale(2).toPlainString())
                : "";
        record(event, sheetId, user, person, saved, saved.getNumber(), due);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** A steward other than its opener approves a PENDING claim: SETTLED, its due fixed at its live due now. */
    @Transactional
    public Claim approve(String sheetId, String user, Long id) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = pendingForSteward(sheetId, user, id);
        if (user.strip().equals(claim.getOpenedBy())) throw new ClaimApprovedByOpenerException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        refuseIfClosed(sheetId, claim.getDate());
        claim.setSettledDue(chargedFigureOf(sheetId, person, claim));
        claim.setStatus(ClaimStatus.SETTLED);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_APPROVED", sheetId, user, person, saved, saved.getNumber(), " due="
                + (saved.getSettledDue() == null ? "n/a" : saved.getSettledDue().setScale(2).toPlainString()));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** A steward rejects a PENDING claim with a reason: OPEN again, bound by its person's last statement if it was. */
    @Transactional
    public Claim reject(String sheetId, String user, Long id, String reason) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = pendingForSteward(sheetId, user, id);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        refuseIfClosed(sheetId, claim.getDate());
        claim.setStatus(ClaimStatus.OPEN);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_REJECTED", sheetId, user, person, saved, saved.getNumber(), " reason=" + reason);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward re-settles a SETTLED claim: its due fixed again at the figure it would have today as an
     * open claim bound by no statement (today's rates, grades, floor and late charge). Refused for anyone
     * else, a claim in any other status, or one dated inside a closed period.
     */
    @Transactional
    public Claim resettle(String sheetId, String user, Long id) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.SETTLED) throw new ClaimStatusRefusedException();
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        refuseIfClosed(sheetId, claim.getDate());
        BigDecimal old = claim.getSettledDue();
        BigDecimal figure = LateCharge.withChargeAsOpen(claim, dueOf(sheetId, person, claim), sheets.today(sheetId),
                settings.latePct(sheetId), roundingOf(sheetId, claim));
        claim.setSettledDue(figure);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_RESETTLED", sheetId, user, person, saved, saved.getNumber(),
                " old=" + figureText(old) + " new=" + figureText(figure));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** A fixed due as an audit line has it: two decimals, or n/a when none. */
    private static String figureText(BigDecimal due) {
        return due == null ? "n/a" : due.setScale(2).toPlainString();
    }

    /**
     * A steward moves a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its number
     * and date, loses any figure a statement bound it to (live again until its new person's next
     * statement), and is listed as its new person's newest claim. A SETTLED or VOID claim cannot move,
     * nor one dated inside a closed period, nor one dated after its new person's retirement date.
     */
    @Transactional
    public Claim reassign(String sheetId, String user, Long id, Long toPersonId) {
        // The snapshot locks the sheet first, so the claim is read once no other change is under way.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        ClaimStatus status = claim.getStatus();
        if (status != ClaimStatus.DRAFT && !status.isOpen()) throw new ClaimStatusRefusedException();
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElseThrow(PersonNotFoundException::new);
        Person to = people.findByIdAndSheetId(toPersonId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (from.getId().equals(to.getId())) throw new ClaimReassignRefusedException();
        refuseIfClosed(sheetId, claim.getDate());
        if (!to.mayClaimOn(claim.getDate())) throw new PersonRetiredException();
        claim.unstate();
        claim.moveTo(to.getId(), claims.nextListed());
        Claim saved = claims.saveAndFlush(claim);
        record("CLAIM_REASSIGNED", sheetId, user, to, saved, saved.getNumber(),
                " from=" + from.getName() + " to=" + to.getName());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** The PENDING claim a steward approves or rejects; refused when not PENDING or not a steward. */
    private Claim pendingForSteward(String sheetId, String user, Long id) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.PENDING) throw new ClaimStatusRefusedException();
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        return claim;
    }

    /**
     * Give an opened claim its number: the sheet's next, or while the sheet numbers by period, the next
     * within the period its date falls in (in no period: the next in the sheet's X sequence).
     */
    private void number(Sheet sheet, Claim claim) {
        if (!sheet.numbersByPeriod()) {
            claim.setNumber(sheet.nextClaimNumber());
            return;
        }
        String period = periods.findBySheetIdOrderByFromDateAsc(sheet.getId()).stream()
                .filter(p -> p.contains(claim.getDate()))
                .map(Period::getName)
                .findFirst().orElse("");
        claim.setNumberWithin(period, claims.lastNumberWithin(sheet.getId(), period) + 1);
    }

    /**
     * A claim's figure now: the due fixed when it was settled, else the due bound by its person's
     * last statement, else its live due; null when it has none.
     */
    public BigDecimal figureOf(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) return claim.getSettledDue();
        if (claim.isBound()) return claim.getStatedDue();
        return dueOf(sheetId, person, claim);
    }

    /**
     * A claim's due now: its figure, with the late charge on top while it is overdue; null when it
     * has none. A statement binds the figure, never the charge.
     */
    public BigDecimal chargedFigureOf(String sheetId, Person person, Claim claim) {
        BigDecimal figure = figureOf(sheetId, person, claim);
        if (claim.getSettledDue() != null) return figure;
        return LateCharge.withCharge(claim, figure, sheets.today(sheetId),
                settings.latePct(sheetId), roundingOf(sheetId, claim));
    }

    /** How the claim's live figures round: by its date, or a correction's by its corrected claim's ({@link Rounding}). */
    private RoundingMode roundingOf(String sheetId, Claim claim) {
        LocalDate basis = Rounding.basisOf(claim,
                Rounding.datesByNumber(claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, claim.getPersonId())));
        return Rounding.of(basis, settings.policyDate(sheetId), sheets.today(sheetId));
    }

    /**
     * A claim's live due: its amount times the rate on its date of its person's grade in force on it, rounded to
     * cents as {@link Rounding} has it, and at least the sheet's floor unless a correction; null with no rate.
     */
    private BigDecimal dueOf(String sheetId, Person person, Claim claim) {
        BigDecimal floor = claim.isCorrection() ? null : settings.floor(sheetId);
        RoundingMode rounding = roundingOf(sheetId, claim);
        return rates.on(sheetId, person.gradeOn(claim.getDate()), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).movePointLeft(2).setScale(2, rounding))
                .map(due -> floor == null ? due : due.max(floor))
                .orElse(null);
    }

    /** Log the status the claim now has: by the user on the sheet's today, with its due at this moment. */
    private void logStatus(String sheetId, String user, Person person, Claim claim) {
        claim.logStatus(user.strip(), sheets.today(sheetId), chargedFigureOf(sheetId, person, claim));
    }

    private void record(String event, String sheetId, String user, Person person, Claim claim) {
        record(event, sheetId, user, person, claim, claim.getNumber(), "");
    }

    /** A claim's audit line; one about a claim with a number has it, then any extra fields. */
    private void record(String event, String sheetId, String user, Person person, Claim claim, String number,
            String extra) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user.strip() + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "") + extra);
    }

    /**
     * Refuse a correction that names no SETTLED claim of its person by number, and a claim that names
     * one but is not a correction.
     */
    private void refuseUnlessCorrects(String sheetId, Long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) throw new CorrectionRefusedException();
            return;
        }
        boolean settled = corrects != null && claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId).stream()
                .anyMatch(c -> corrects.equals(c.getNumber()) && c.getStatus() == ClaimStatus.SETTLED);
        if (!settled) throw new CorrectionRefusedException();
    }

    /** Whether a clerk settles a claim whose due is above the clerk limit; a due of n/a is within any. */
    private boolean overClerkLimit(String sheetId, String user, BigDecimal due) {
        if (settings.roleOf(sheetId, user) == Role.STEWARD) return false;
        BigDecimal limit = settings.clerkLimit(sheetId);
        return limit != null && due != null && due.compareTo(limit) > 0;
    }

    /** Refuse a change to a claim dated inside a closed period; the caller holds the sheet's lock. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        if (periods.closedOn(sheetId, date)) throw new PeriodClosedException();
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }
}
