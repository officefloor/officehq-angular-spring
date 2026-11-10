package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The claims on a sheet: added to a person and edited (amount, date, note). A claim given no date
 * takes the sheet's today. A claim starts as DRAFT and moves through its statuses (see
 * {@link ClaimStatus}); a change its status does not allow is refused. Only a DRAFT can be edited:
 * to change an open claim, void it and add a new one. Settling a claim fixes its
 * due at the rate its person's grade has then for the claim's date; an open claim's due stays live.
 * A claim records who opened it, and that user cannot settle it (four eyes). A claim bound by its
 * person's last statement keeps the figure it had then, and settling it fixes that figure. No claim
 * dated inside a closed period can be added, edited (to or from such a date) or change status. No
 * claim can be added or edited to a date after its person's retirement date. A clerk settles a claim
 * only when its figure is at or below the sheet's clerk limit (a claim with no figure is within any
 * limit); a clerk settling one above it makes it PENDING instead. A steward settles any claim, and
 * approves a PENDING claim (SETTLED, its due fixed then) or rejects it with a reason (back to OPEN);
 * the user who opened a claim cannot approve it. A PENDING claim is not bound by a statement: it
 * shows its live figure, and is bound again by the last statement once rejected. A claim's live due is never below the sheet's floor. An
 * overdue claim's due gains a late charge on top of its figure (see {@link LateCharge}); a statement
 * binds only the figure under it, and settling fixes the charge into the settled due. A claim with
 * a negative amount is a correction: it is allowed only when it names, by number, a SETTLED claim
 * of the same person that it corrects. Its due has no floor and no late charge. A steward can move
 * a DRAFT, OPEN or PENDING claim to another person: it keeps its number and date, loses any figure
 * a statement bound it to, and is listed as its new person's newest claim. A steward can re-settle
 * a SETTLED claim: its due is fixed again at the figure it would have today as an open claim bound
 * by no statement (today's rates, grades, floor and late charge). An OPEN claim can be deferred to
 * a date after the sheet's today: until the sheet's today reaches it the claim counts in no total,
 * is not overdue, cannot be settled and is left unbound by a statement; from then on it is an OPEN
 * claim like any other. A claim has a proofs count, raised by one at a time while it is not SETTLED
 * or VOID. A claim whose figure is above the proof threshold in force on its date cannot be settled
 * or approved while it has no proofs: that is refused before the clerk limit is considered.
 * Who may open, settle, void or move a claim is the sheet's permission matrix (see
 * {@link Sheet#permits}): an action the user's role may not take is refused (403).
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateRepository rates;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final PeriodService periods;
    private final RoleService roles;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateRepository rates, SheetService sheets,
            Audit audit, RollupService rollup, PeriodService periods, RoleService roles) {
        this.roles = roles;
        this.rollup = rollup;
        this.periods = periods;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
    }

    /**
     * A claim's figure under any late charge: the due fixed when settled, else the one bound by a
     * statement (not while PENDING), else the live due; null when it has none.
     */
    public BigDecimal figure(String sheetId, Person person, Claim claim) {
        if (claim.getStatus() == ClaimStatus.SETTLED) {
            return claim.getSettledDue();
        }
        return claim.isStated() && claim.getStatus() != ClaimStatus.PENDING ? claim.getStatedDue()
                : due(sheetId, person, claim);
    }

    /** A claim's figure with its late charge on the sheet's today added on top; null when it has none. */
    public BigDecimal charged(String sheetId, Person person, Claim claim) {
        return LateCharge.withCharge(sheets.sheet(sheetId), claim, figure(sheetId, person, claim));
    }

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Person person = person(sheetId, personId);
        LocalDate dated = dateOrToday(sheetId, date);
        periods.refuseIfClosed(sheetId, dated);
        refuseIfRetired(person, dated);
        String corrected = corrected(sheetId, personId, amount, corrects);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = new Claim(sheetId, personId, amount, dated,
                dated.plusDays(sheets.sheet(sheetId).termsFor(person.gradeOn(dated), dated)), note, corrected);
        logStatus(sheetId, user, person, claim);
        Claim added = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_ADDED", sheetId, user, person, added));
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited.");
        }
        LocalDate dated = dateOrToday(sheetId, date);
        periods.refuseIfClosed(sheetId, claim.getDate());
        periods.refuseIfClosed(sheetId, dated);
        refuseIfRetired(person(sheetId, claim.getPersonId()), dated);
        String corrected = corrected(sheetId, claim.getPersonId(), amount, corrects);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(amount);
        claim.setDate(dated);
        claim.setNote(note);
        claim.setCorrects(corrected);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_EDITED", sheetId, user, person(sheetId, saved.getPersonId()), saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Move a claim to a new status, refused (409) when its current status does not allow it,
     * refused (403) when the user who opened it tries to settle it, and refused (422) settling a
     * claim above the proof threshold with no proofs. A clerk settling one whose
     * figure is above the sheet's clerk limit makes it PENDING instead.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next) {
        return changeStatus(sheetId, user, id, next, null, false);
    }

    /**
     * Approve a PENDING claim: it becomes SETTLED, its due fixed at its live figure with its late
     * charge on the sheet's today. Refused (409) when not PENDING, and (403) for a clerk or the
     * user who opened it.
     */
    @Transactional
    public Claim approve(String sheetId, String user, long id) {
        return changeStatus(sheetId, user, id, ClaimStatus.SETTLED, null, true);
    }

    /**
     * Reject a PENDING claim with a reason: it is OPEN again, bound again by the last statement if
     * that statement included it. Refused (409) when not PENDING, and (403) for a clerk.
     */
    @Transactional
    public Claim reject(String sheetId, String user, long id, String reason) {
        return changeStatus(sheetId, user, id, ClaimStatus.OPEN, reason, true);
    }

    /** Move a claim to a new status; {@code review} for a steward's approval or rejection of a PENDING claim. */
    private Claim changeStatus(String sheetId, String user, long id, ClaimStatus next, String reason,
            boolean review) {
        if (!review) {
            roles.refuseUnlessPermitted(sheetId, user, switch (next) {
                case OPEN -> "open";
                case SETTLED -> "settle";
                case VOID -> "void";
                default -> throw new IllegalStateException();
            });
        }
        // lock the sheet's numbering before reading the claim, so a claim is opened (and numbered) once
        if (next == ClaimStatus.OPEN) {
            sheets.lock(sheetId);
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        ClaimStatus was = claim.getStatus();
        // only a steward's review moves a PENDING claim on, other than voiding it
        if (!was.canBecome(next) || (next != ClaimStatus.VOID && review != (was == ClaimStatus.PENDING))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        periods.refuseIfClosed(sheetId, claim.getDate());
        if (next == ClaimStatus.SETTLED && claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim deferred until " + claim.getDeferredUntil() + " cannot be settled before then.");
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot " + (review ? "approve" : "settle") + " it.");
        }
        if (review && !isSteward(sheetId, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a steward can approve or reject a PENDING claim.");
        }
        Person person = person(sheetId, claim.getPersonId());
        // settling fixes the figure with its late charge as it stands on the sheet's today (a
        // PENDING claim's figure is live)
        BigDecimal settling = next == ClaimStatus.SETTLED ? charged(sheetId, person, claim) : null;
        if (next == ClaimStatus.SETTLED && needsProof(sheetId, claim, settling)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A claim due above the proof threshold cannot be " + (review ? "approved" : "settled")
                            + " without a proof.");
        }
        if (next == ClaimStatus.SETTLED && !review && aboveClerkLimit(sheetId, user, settling)) {
            next = ClaimStatus.PENDING;
            settling = null;
        }
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN && !review) {
            claim.setNumber(sheets.nextClaimNumber(sheetId, claim.getDate()));
            claim.setOpenedBy(user);
        } else if (next == ClaimStatus.SETTLED) {
            claim.setSettledDue(settling);
        }
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> review ? "CLAIM_REJECTED" : "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> review ? "CLAIM_APPROVED" : "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        audit.recordOnCommit(record(event, sheetId, user, person, saved)
                + (saved.getNumber() != null ? " number=" + saved.getNumber() : "")
                + (next == ClaimStatus.SETTLED ? " due=" + dueText(saved.getSettledDue()) : "")
                + (reason != null ? " reason=" + reason : ""));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Defer an OPEN claim until a date after the sheet's today: until the sheet's today reaches it
     * the claim counts in no total, is not overdue, cannot be settled and is left unbound by a
     * statement (deferring unbinds it). Refused (409) for a claim that is not OPEN, (400) for a date
     * not after the sheet's today and (423) when dated inside a closed period.
     */
    @Transactional
    public Claim defer(String sheetId, String user, long id, LocalDate until) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be deferred.");
        }
        if (!until.isAfter(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A claim can only be deferred to a date after the sheet's today.");
        }
        periods.refuseIfClosed(sheetId, claim.getDate());
        Person person = person(sheetId, claim.getPersonId());
        claim.setDeferredUntil(until);
        claim.bind(false, null);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_DEFERRED", sheetId, user, person, saved)
                + (saved.getNumber() != null ? " number=" + saved.getNumber() : "") + " until=" + until);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Raise a claim's proofs count by one. Refused (409) for a SETTLED or VOID claim and (423) when
     * dated inside a closed period.
     */
    @Transactional
    public Claim addProof(String sheetId, String user, long id) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot have a proof added.");
        }
        periods.refuseIfClosed(sheetId, claim.getDate());
        Person person = person(sheetId, claim.getPersonId());
        claim.addProof();
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_PROOF_ADDED", sheetId, user, person, saved)
                + (saved.getNumber() != null ? " number=" + saved.getNumber() : "") + " proofs=" + saved.getProofs());
        return saved;
    }

    /**
     * Re-settle a SETTLED claim: fix its due again at the figure it would have on the sheet's today
     * as an open claim bound by no statement, with its late charge. Refused (403) for anyone but a
     * steward, (409) for a claim that is not SETTLED and (423) when dated inside a closed period.
     */
    @Transactional
    public Claim resettle(String sheetId, String user, long id) {
        if (!isSteward(sheetId, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can re-settle a claim.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-settled.");
        }
        periods.refuseIfClosed(sheetId, claim.getDate());
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal old = claim.getSettledDue();
        BigDecimal settling = LateCharge.withChargeAsOpen(sheets.sheet(sheetId), claim, due(sheetId, person, claim));
        claim.setSettledDue(settling);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_RESETTLED", sheetId, user, person, saved)
                + (saved.getNumber() != null ? " number=" + saved.getNumber() : "")
                + " old=" + dueText(old) + " new=" + dueText(settling));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Move a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its number and
     * date, is unbound from any statement (live again until its new person's next statement), and
     * is listed first under its new person. Refused (403) for a role the permission matrix does not allow, (409) for a SETTLED or VOID
     * claim or a move to the person it is already on, (400) for a correction (it must stay with
     * the claim it corrects), (423) when dated inside a closed period and (422) when dated after
     * the new person's retirement date.
     */
    @Transactional
    public Claim reassign(String sheetId, String user, long id, long toPersonId) {
        roles.refuseUnlessPermitted(sheetId, user, "reassign");
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be moved.");
        }
        Person from = person(sheetId, claim.getPersonId());
        Person to = person(sheetId, toPersonId);
        if (from.getId().equals(to.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That claim is already on that person.");
        }
        if (claim.isCorrection()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A correction cannot be moved.");
        }
        periods.refuseIfClosed(sheetId, claim.getDate());
        refuseIfRetired(to, claim.getDate());
        claim.setPersonId(to.getId());
        claim.bind(false, null);
        claims.saveAndFlush(claim);
        claims.listFirst(claim.getId());
        Claim saved = claims.findByIdAndSheetId(id, sheetId).orElseThrow();
        audit.recordOnCommit(record("CLAIM_REASSIGNED", sheetId, user, to, saved)
                + (saved.getNumber() != null ? " number=" + saved.getNumber() : "")
                + " from=" + from.getName() + " to=" + to.getName());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** What a bulk open did: how many claims were opened and how many refused, and the claims opened. */
    public record BulkOpened(int opened, int refused, List<Claim> claims) {
    }

    /**
     * Open the given claims together, in list order (the order people were added, newest claim
     * first within a person), so they are numbered in that order. A claim that cannot be opened
     * (not on the sheet, not DRAFT, or dated inside a closed period) is refused and left as it is.
     */
    @Transactional
    public BulkOpened bulkOpen(String sheetId, String user, Collection<Long> ids) {
        List<Claim> selected = inListOrder(sheetId, ids);
        int refused = (int) ids.stream().distinct().count() - selected.size();
        List<Claim> opened = new ArrayList<>();
        boolean permitted = sheets.sheet(sheetId).permits("open", roles.roleOf(sheetId, user));
        for (Claim claim : selected) {
            if (!permitted || claim.getStatus() != ClaimStatus.DRAFT || periods.isClosed(sheetId, claim.getDate())) {
                refused++;
                continue;
            }
            opened.add(changeStatus(sheetId, user, claim.getId(), ClaimStatus.OPEN));
        }
        return new BulkOpened(opened.size(), refused, opened);
    }

    /** What a bulk settle did: how many claims were settled (or made PENDING) and how many refused, and the claims changed. */
    public record BulkSettled(int settled, int refused, List<Claim> claims) {
    }

    /**
     * Settle the given claims together, in list order (the order people were added, newest claim
     * first within a person), each by exactly the rules of settling one claim. A claim a clerk's
     * settle makes PENDING counts as settled; a claim that cannot be settled is refused and left
     * as it is.
     */
    @Transactional
    public BulkSettled bulkSettle(String sheetId, String user, Collection<Long> ids) {
        List<Claim> selected = inListOrder(sheetId, ids);
        int refused = (int) ids.stream().distinct().count() - selected.size();
        List<Claim> settled = new ArrayList<>();
        for (Claim claim : selected) {
            try {
                settled.add(changeStatus(sheetId, user, claim.getId(), ClaimStatus.SETTLED));
            } catch (ResponseStatusException refusedOne) {
                // every refusal is checked before the claim is changed, so it is left as it is
                refused++;
            }
        }
        return new BulkSettled(settled.size(), refused, settled);
    }

    /** The given claims on the sheet in list order: people as added, newest claim first within a person. */
    private List<Claim> inListOrder(String sheetId, Collection<Long> ids) {
        Map<Long, Integer> personOrder = new HashMap<>();
        List<Person> listed = people.findBySheetIdOrderByIdAsc(sheetId);
        for (int i = 0; i < listed.size(); i++) {
            personOrder.put(listed.get(i).getId(), i);
        }
        return ids.stream().distinct()
                .flatMap(id -> claims.findByIdAndSheetId(id, sheetId).stream())
                .sorted(Comparator.<Claim>comparingInt(c -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE))
                        .thenComparing(Claim::getListed, Comparator.reverseOrder()))
                .toList();
    }

    /**
     * The due on a claim's amount at the rate for the person's grade in force on the claim's date,
     * rounded to the cent as the claim rounds (see {@link Rounding}) and raised to the floor in force on the claim's date (a correction's is not); null with no
     * rate in force then.
     */
    public BigDecimal due(String sheetId, Person person, Claim claim) {
        Sheet sheet = sheets.sheet(sheetId);
        RoundingMode rounding = Rounding.mode(sheet, roundingDate(sheetId, claim));
        return rates.findInForce(sheetId, person.gradeOn(claim.getDate()), claim.getDate()).map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2, rounding))
                .map(due -> claim.isCorrection() ? due : due.max(sheet.floorOn(claim.getDate())))
                .orElse(null);
    }

    /** The date a claim rounds by: its own, or for a correction that of the claim it corrects. */
    private LocalDate roundingDate(String sheetId, Claim claim) {
        if (!claim.isCorrection() || claim.getCorrects() == null) {
            return claim.getDate();
        }
        return Rounding.dateOf(claim, claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, claim.getPersonId()));
    }

    /** Log the claim's status as it is now: by the user, on the sheet's today, with its due as computed now. */
    private void logStatus(String sheetId, String user, Person person, Claim claim) {
        claim.log(new ClaimLogEntry(claim.getStatus(), user, sheets.today(sheetId),
                dueText(charged(sheetId, person, claim))));
    }

    private static String dueText(BigDecimal due) {
        return due == null ? "n/a" : due.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }

    /** Whether a claim settling at the figure is above the proof threshold in force on its date and has no proofs. */
    private boolean needsProof(String sheetId, Claim claim, BigDecimal figure) {
        BigDecimal threshold = sheets.sheet(sheetId).proofThresholdOn(claim.getDate());
        return claim.getProofs() == 0 && figure != null && threshold != null && figure.compareTo(threshold) > 0;
    }

    /** Whether the user is a clerk settling a claim whose figure is above the sheet's clerk limit. */
    private boolean aboveClerkLimit(String sheetId, String user, BigDecimal figure) {
        BigDecimal limit = sheets.clerkLimit(sheetId);
        return figure != null && limit != null && figure.compareTo(limit) > 0 && !isSteward(sheetId, user);
    }

    private boolean isSteward(String sheetId, String user) {
        return Role.STEWARD.equals(roles.roleOf(sheetId, user));
    }

    /**
     * The number a claim corrects: none unless its amount is negative, when it must name the number
     * of a SETTLED claim of the same person, else it is refused (400).
     */
    private String corrected(String sheetId, long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            return null;
        }
        boolean settled = corrects != null && claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId).stream()
                .anyMatch(c -> corrects.equals(c.getNumber()) && c.getStatus() == ClaimStatus.SETTLED);
        if (!settled) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A negative amount must correct a SETTLED claim of the same person, by its number.");
        }
        return corrects;
    }

    /** Refuse (422) a claim dated after its person's retirement date. */
    private static void refuseIfRetired(Person person, LocalDate date) {
        if (person.isRetired() && date.isAfter(person.getRetiredFrom())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "That claim's date is after its person's retirement date.");
        }
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
    }

    private static String record(String event, String sheetId, String user, Person person, Claim claim) {
        return event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName() + " amount="
                + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString() + " date=" + claim.getDate();
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }
}
