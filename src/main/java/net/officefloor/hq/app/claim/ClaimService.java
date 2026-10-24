package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

/** The claims people on each sheet make. A claim left without a date is dated the sheet's today. A claim's
 * due-by is fixed when it is added, from the terms of its person's grade in force on its date. Only a
 * DRAFT claim can be edited. No claim dated inside a closed period can be added, edited or change
 * status, and none can be added or edited to a date after its person's retirement date. A claim with a
 * negative amount is a correction: it is refused unless it names, by number, a SETTLED claim of the same
 * person; a claim that is not a correction names none. */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final ClaimLogRepository logs;
    private final PersonRepository people;
    private final RollupService rollup;
    private final SheetService sheets;
    private final PeriodRepository periods;
    private final SettingsService settings;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, ClaimLogRepository logs, PersonRepository people, RollupService rollup, SheetService sheets,
            PeriodRepository periods, SettingsService settings, Audit audit) {
        this.claims = claims;
        this.logs = logs;
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
        return claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
    }

    /** The status logs of the claims, by claim id, each oldest first. */
    @Transactional(readOnly = true)
    public Map<Long, List<ClaimLog>> logsOf(List<Claim> of) {
        if (of.isEmpty()) return Map.of();
        return logs.findByClaimIdInOrderByIdAsc(of.stream().map(Claim::getId).toList()).stream()
                .collect(Collectors.groupingBy(ClaimLog::getClaimId));
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
        Claim claim = new Claim(sheetId, personId, amount, dated, noteOf(note), corrects);
        claim.setDueBy(dated.plusDays(settings.termsFor(sheetId, person.gradeOn(dated), dated)));
        claim.setListed(sheets.nextInSequence(sheetId, "listed"));
        claim = claims.saveAndFlush(claim);
        log(user, person, claim);
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
     * it. A claim deferred on the sheet's today cannot be settled, nor one without proofs whose due is
     * above the proof threshold in force on its date (refused before the clerk limit is considered). A voided claim keeps its number, if it had one. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus next) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(next) || (claim.getStatus() == ClaimStatus.PENDING && next != ClaimStatus.VOID)) {
            throw new ClaimStatusChangeException();
        }
        if (next == ClaimStatus.SETTLED && claim.isDeferredOn(sheets.today(sheetId))) throw new ClaimStatusChangeException();
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
            refuseUnproved(claim, due);
            if (overLimit(sheetId, user, due)) next = ClaimStatus.PENDING;
            else claim.setSettledDue(due);
        }
        claim.setStatus(next);
        claim.setNumber(number);
        claims.flush();
        log(user, person, claim);
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

    /** Defer an OPEN claim until {@code until}, a date after the sheet's today: until the sheet's today
     * reaches it the claim counts in no total, is not overdue, cannot be settled and is left unbound by a
     * statement. Refused unless the claim is OPEN, when {@code until} is not after the sheet's today, and
     * when the claim is dated in a closed period. */
    @Transactional
    public Claim defer(String sheetId, String user, long claimId, LocalDate until) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.OPEN) throw new ClaimStatusChangeException();
        if (!until.isAfter(sheets.today(sheetId))) throw new ClaimDeferException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setDeferredUntil(until);
        claims.flush();
        record("CLAIM_DEFERRED", user, person, claim, claim.getNumber(), "until=" + until);
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** A steward's approval of a PENDING claim: it becomes SETTLED, fixing what is due on it then (its
     * live figure, which no statement binds while it is PENDING, together with its late charge then).
     * Refused unless the claim is PENDING, when the user is not a steward or opened the claim, and when it
     * has no proofs and that due is above the proof threshold in force on its date. */
    @Transactional
    public Claim approve(String sheetId, String user, long claimId) {
        Claim claim = lockPending(sheetId, user, claimId);
        if (user.equals(claim.getOpenedBy())) throw new ClaimNotStewardException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal figure = rollup.dueOf(claim, person);
        BigDecimal due = figure == null ? null : figure.add(rollup.lateChargeOf(claim, figure));
        refuseUnproved(claim, due);
        claim.setSettledDue(due);
        claim.setStatus(ClaimStatus.SETTLED);
        claims.flush();
        log(user, person, claim);
        record("CLAIM_APPROVED", user, person, claim, claim.getNumber(), "due=" + dueText(due));
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** Raise the claim's proofs count by one. Refused when the claim is SETTLED or VOID, and when it is
     * dated in a closed period. */
    @Transactional
    public Claim addProof(String sheetId, String user, long claimId) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ClaimStatusChangeException();
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        claim.addProof();
        claims.flush();
        record("CLAIM_PROOF_ADDED", user, person, claim, claim.getNumber(), "proofs=" + claim.getProofs());
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
        log(user, person, claim);
        record("CLAIM_REJECTED", user, person, claim, claim.getNumber(), "reason=" + reason);
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** A steward's move of a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its
     * number and date, loses any figure a statement bound it to (it is live again until its new
     * person's next statement), and is listed as its new person's newest claim. Refused when the user
     * is not a steward, when the claim is SETTLED or VOID, is a correction (it corrects a claim of its
     * own person) or already belongs to the person, when it is dated in a closed period, and when it
     * is dated after the new person's retirement date. */
    @Transactional
    public Claim reassign(String sheetId, String user, long claimId, long personId) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        refuseClosed(sheetId, claim.getDate());
        ClaimStatus status = claim.getStatus();
        if ((status != ClaimStatus.DRAFT && status != ClaimStatus.OPEN && status != ClaimStatus.PENDING)
                || claim.isCorrection() || claim.getPersonId() == personId) {
            throw new ClaimReassignException();
        }
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        Person to = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        to.requireClaimable(claim.getDate());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setPersonId(to.getId());
        claim.unbind();
        claim.setListed(sheets.nextInSequence(sheetId, "listed"));
        claims.flush();
        record("CLAIM_REASSIGNED", user, to, claim, claim.getNumber(), "from=" + from.getName(), "to=" + to.getName());
        rollup.recordSince(sheetId, user, before);
        return claim;
    }

    /** A steward's re-settle of a SETTLED claim: it is fixed again at what would be due on it now were
     * it OPEN and bound by no statement (its person's rate and grade, the sheet's floor and its late
     * charge, all as they are now). Refused unless the claim is SETTLED, when the user is not a
     * steward, and when it is dated in a closed period. */
    @Transactional
    public Claim resettle(String sheetId, String user, long claimId) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD) throw new ClaimNotStewardException();
        refuseClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.SETTLED) throw new ClaimStatusChangeException();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal old = claim.getSettledDue();
        BigDecimal due = rollup.openShownOf(claim, person);
        claim.setSettledDue(due);
        claims.flush();
        log(user, person, claim);
        record("CLAIM_RESETTLED", user, person, claim, claim.getNumber(), "old=" + dueText(old), "new=" + dueText(due));
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

    /** Write the status the claim has now to its log, with the sheet's today and what is due on it as
     * it shows now. */
    private void log(String user, Person person, Claim claim) {
        logs.save(new ClaimLog(claim.getId(), claim.getStatus(), user, sheets.today(claim.getSheetId()),
                rollup.shownOf(claim, person)));
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

    /** Refuse a settle or approval of the claim, whose due is {@code due}, while it has no proofs and that
     * due is above the proof threshold in force on its date (a due that is n/a is within any threshold). */
    private void refuseUnproved(Claim claim, BigDecimal due) {
        if (due == null || claim.getProofs() > 0) return;
        BigDecimal threshold = settings.proofThresholdOn(claim.getSheetId(), claim.getDate());
        if (threshold != null && due.compareTo(threshold) > 0) throw new ClaimProofRequiredException();
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
