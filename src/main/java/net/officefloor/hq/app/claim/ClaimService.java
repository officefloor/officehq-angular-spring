package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The claims people on each sheet make. A claim left without a date is dated the sheet's today. Only a
 * DRAFT claim can be edited. No claim dated inside a closed period can be added, edited or change
 * status, and none can be added or edited to a date after its person's retirement date. A claim with a
 * negative amount is a correction: it is refused unless it names, by number, a SETTLED claim of the same
 * person; a claim that is not a correction names none. */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RollupService rollup;
    private final SheetService sheets;
    private final PeriodRepository periods;
    private final SettingsService settings;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RollupService rollup, SheetService sheets,
            PeriodRepository periods, SettingsService settings, Audit audit) {
        this.claims = claims;
        this.people = people;
        this.rollup = rollup;
        this.sheets = sheets;
        this.periods = periods;
        this.settings = settings;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate dated = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, dated);
        person.requireClaimable(dated);
        refuseBadCorrection(sheetId, personId, amount, corrects);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount, dated, noteOf(note), corrects));
        record("CLAIM_ADDED", user, person, claim, claim.getNumber());
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    @Transactional
    public Claim update(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        LocalDate dated = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDate());
        refuseClosed(sheetId, dated);
        if (claim.getStatus() != ClaimStatus.DRAFT) throw new ClaimNotEditableException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        person.requireClaimable(dated);
        refuseBadCorrection(sheetId, person.getId(), amount, corrects);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(amount);
        claim.setCorrects(corrects);
        claim.setDate(dated);
        claim.setNote(noteOf(note));
        claims.flush();
        record("CLAIM_EDITED", user, person, claim, claim.getNumber());
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** Move the claim to {@code next}; refused when its current status does not allow it (a PENDING
     * claim can only be voided here: it is approved or rejected instead). Settling it fixes what is due
     * on it then (the figure it is bound to, when a statement bound it, otherwise its person's rate then)
     * together with its late charge then (none on a correction); the user who opened it cannot settle it,
     * and a clerk's settle of it when that due is above the sheet's clerk limit (a due that is n/a is
     * within any limit) leaves it PENDING instead, fixing nothing, until a steward approves or rejects
     * it. A voided claim keeps its number, if it had one. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus next) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(next) || (claim.getStatus() == ClaimStatus.PENDING && next != ClaimStatus.VOID)) {
            throw new ClaimStatusChangeException();
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) throw new ClaimSelfSettleException();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String number = claim.getNumber();
        if (next == ClaimStatus.OPEN) {
            number = nextNumber(sheetId, claim.getDate());
            claim.setOpenedBy(user);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        if (next == ClaimStatus.SETTLED) {
            BigDecimal figure = claim.isStated() ? claim.getStatedDue() : rollup.dueOf(claim, person);
            BigDecimal due = figure == null ? null : figure.add(rollup.lateChargeOf(claim, figure));
            if (overLimit(sheetId, user, due)) next = ClaimStatus.PENDING;
            else claim.setSettledDue(due);
        }
        claim.setStatus(next);
        claim.setNumber(number);
        claims.flush();
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        if (next == ClaimStatus.SETTLED) {
            record(event, user, person, claim, number, "due=" + dueText(claim.getSettledDue()));
        } else {
            record(event, user, person, claim, number);
        }
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** A steward's approval of a PENDING claim: it becomes SETTLED, fixing what is due on it then (its
     * live figure, which no statement binds while it is PENDING, together with its late charge then).
     * Refused unless the claim is PENDING, and when the user is not a steward or opened the claim. */
    @Transactional
    public Claim approve(String sheetId, String user, long claimId) {
        Claim claim = lockPending(sheetId, user, claimId);
        if (user.equals(claim.getOpenedBy())) throw new ClaimNotStewardException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal figure = rollup.dueOf(claim, person);
        BigDecimal due = figure == null ? null : figure.add(rollup.lateChargeOf(claim, figure));
        claim.setSettledDue(due);
        claim.setStatus(ClaimStatus.SETTLED);
        claims.flush();
        record("CLAIM_APPROVED", user, person, claim, claim.getNumber(), "due=" + dueText(due));
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** A steward's rejection of a PENDING claim, for {@code reason}: it goes back to OPEN, bound again
     * by its person's last statement if that statement included it. Refused unless the claim is
     * PENDING, and when the user is not a steward. */
    @Transactional
    public Claim reject(String sheetId, String user, long claimId, String reason) {
        Claim claim = lockPending(sheetId, user, claimId);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        claims.flush();
        record("CLAIM_REJECTED", user, person, claim, claim.getNumber(), "reason=" + reason);
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** The PENDING claim, locked, for a steward to approve or reject. */
    private Claim lockPending(String sheetId, String user, long claimId) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.PENDING) throw new ClaimStatusChangeException();
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        return claim;
    }

    private static String dueText(BigDecimal due) {
        return due == null ? "n/a" : due.toPlainString();
    }

    /** The figure the claim shows and totals now, without its late charge; null when it has no figure
     * (see {@link RollupService#figureOf}). */
    public BigDecimal figureOf(Claim claim, Person person) {
        return rollup.figureOf(claim, person);
    }

    /** What is due on the claim as it shows and totals now, its late charge included; null when it has
     * no figure (see {@link RollupService#shownOf}). */
    public BigDecimal shownOf(Claim claim, Person person) {
        return rollup.shownOf(claim, person);
    }

    /** Record a change to the claim; {@code number}, the number it has (or had), follows when there is one,
     * then any {@code extra} fields. */
    private void record(String event, String user, Person person, Claim claim, String number, String... extra) {
        List<String> fields = new ArrayList<>(List.of("person=" + person.getName(),
                "amount=" + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                "date=" + claim.getDate()));
        if (number != null) fields.add("number=" + number);
        fields.addAll(List.of(extra));
        audit.change(event, claim.getSheetId(), user, fields.toArray(String[]::new));
    }

    /** The number a claim dated {@code date} is given when opened now: within the period containing
     * that date ({@code <period name>-<n>}, or {@code X-<n>} when none does) while the sheet numbers by
     * period, otherwise the next sheet-wide number. */
    private String nextNumber(String sheetId, LocalDate date) {
        if (!sheets.numbersByPeriod(sheetId)) return String.valueOf(sheets.nextClaimNumber(sheetId));
        return periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter(p -> !date.isBefore(p.getFrom()) && !date.isAfter(p.getTo()))
                .findFirst()
                .map(p -> p.getName() + "-" + sheets.nextInSequence(sheetId, "period:" + p.getId()))
                .orElseGet(() -> "X-" + sheets.nextInSequence(sheetId, "none"));
    }

    /** Whether a settle by {@code user} of a claim whose due is {@code due} needs a steward's approval:
     * the user is a clerk and the due is above the sheet's clerk limit. */
    private boolean overLimit(String sheetId, String user, BigDecimal due) {
        if (due == null || settings.roleOf(sheetId, user) == Role.STEWARD) return false;
        BigDecimal limit = settings.clerkLimit(sheetId);
        return limit != null && due.compareTo(limit) > 0;
    }

    /** Refuse a correction (a negative {@code amount}) that does not name, by number, a SETTLED claim of
     * the person, and a claim that names one without being a correction. */
    private void refuseBadCorrection(String sheetId, Long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) throw new ClaimCorrectionException();
            return;
        }
        if (corrects == null) throw new ClaimCorrectionException();
        claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .filter(corrected -> corrected.getStatus() == ClaimStatus.SETTLED)
                .orElseThrow(ClaimCorrectionException::new);
    }

    /** Refuse a change that touches {@code date} when a closed period of the sheet contains it. */
    private void refuseClosed(String sheetId, LocalDate date) {
        if (periods.closedOn(sheetId, date)) throw new ClaimPeriodClosedException();
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
