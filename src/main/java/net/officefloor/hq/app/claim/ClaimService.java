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
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.Watch;
import net.officefloor.hq.app.person.WatchRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.voidreason.VoidReasonService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The claims on a sheet: added to a person and edited (amount, date, note, external reference). A
 * claim's reference, when it has one, is unique on its sheet without regard to case. A claim given no date
 * takes the sheet's today. A claim starts as DRAFT and moves through its statuses (see
 * {@link ClaimStatus}); a change its status does not allow is refused. Only a DRAFT can be edited:
 * to change an open claim, void it and add a new one. Settling a claim fixes its
 * due at the rate its person's grade has then for the claim's date; an open claim's due stays live.
 * A claim records who opened it, and that user cannot settle it (four eyes). A claim bound by its
 * person's last statement keeps the figure it had then, and settling it fixes that figure. A claim
 * belongs to the period its due-by falls in (fixed at creation: an edit, re-date or move leaves it).
 * No claim due inside a closed period can be added, edited or change status. No
 * claim can be added or edited to a date after its person's retirement date. A clerk settles a claim
 * only when its figure is at or below the sheet's clerk limit (a claim with no figure is within any
 * limit); a clerk settling one above it makes it PENDING instead. A steward settles any claim, and
 * approves a PENDING claim (SETTLED, its due fixed then) or rejects it with a reason (back to OPEN);
 * the user who opened a claim cannot approve it. The user who last edited a claim (through its form or
 * its row's amount) can neither settle nor approve it, as its opener cannot. A PENDING claim is not bound by a statement: it
 * shows its live figure, and is bound again by the last statement once rejected. An overdue claim's due gains a late charge on its
 * figure (see {@link LateCharge}), and the floor is applied last: a claim's live due is never below
 * the sheet's floor. A statement binds only the rate figure, not the charge nor the floor, and settling fixes the charge into the settled due. A claim with
 * a negative amount is a correction: it is allowed only when it names, by number, a SETTLED claim
 * of the same person that it corrects. Its due has no floor and no late charge. A steward can move
 * a DRAFT, OPEN or PENDING claim to another person: it keeps its number and date, loses any figure
 * a statement bound it to, and is listed as its new person's newest claim. A steward can re-settle
 * a SETTLED claim: its due is fixed again at the figure it would have today as an open claim bound
 * by no statement (today's rates, grades, floor and late charge). An OPEN claim can be deferred to
 * a date after the sheet's today: until the sheet's today reaches it the claim counts in no total,
 * is not overdue, cannot be settled and is left unbound by a statement; from then on it is an OPEN
 * claim like any other. A claim has a proofs count, raised by one at a time while it is not SETTLED
 * or VOID. An OPEN claim can be split: an amount split off it becomes a new OPEN claim (see
 * {@link #split}). A claim whose figure is above the proof threshold in force on its date cannot be settled
 * or approved while it has no proofs: that is refused before the clerk limit is considered. A claim
 * whose due is above the note threshold in force on its date cannot be opened while its note is empty.
 * Who may open, settle, void or move a claim is the sheet's permission matrix (see
 * {@link Sheet#permits}): an action the user's role may not take is refused (403). When a claim is
 * settled, approved or re-settled, each user watching its person is notified, in the order they
 * started watching. A steward may void a SETTLED claim within {@value #VOID_SETTLED_DAYS} days of its
 * settlement (the date its status log records it SETTLED) by the sheet's today: it keeps its number and
 * its frozen figure, which leaves every total.
 */
@Service
public class ClaimService {

    /** How many days after its settlement a steward may still void a SETTLED claim. */
    static final int VOID_SETTLED_DAYS = 7;

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateRepository rates;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final PeriodService periods;
    private final RoleService roles;
    private final WatchRepository watches;
    private final HolidayService holidays;
    private final UsedTagRepository usedTags;
    private final VoidReasonService voidReasons;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateRepository rates, SheetService sheets,
            Audit audit, RollupService rollup, PeriodService periods, RoleService roles, WatchRepository watches,
            HolidayService holidays, UsedTagRepository usedTags, VoidReasonService voidReasons) {
        this.voidReasons = voidReasons;
        this.holidays = holidays;
        this.usedTags = usedTags;
        this.roles = roles;
        this.watches = watches;
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
        if (isFrozen(claim)) {
            return claim.getSettledDue();
        }
        return claim.isStated() && claim.getStatus() != ClaimStatus.PENDING ? claim.getStatedDue()
                : due(sheetId, person, claim);
    }

    /** Whether the claim's figure is the due fixed when settled: SETTLED, or VOID once settled. */
    private static boolean isFrozen(Claim claim) {
        return claim.getStatus() == ClaimStatus.SETTLED
                || (claim.getStatus() == ClaimStatus.VOID && claim.getSettledDue() != null);
    }

    /**
     * A claim's due on the sheet's today: the due fixed when settled, else its figure with its late
     * charge added on top, raised to the floor (see {@link LateCharge}); null when it has none.
     */
    public BigDecimal charged(String sheetId, Person person, Claim claim) {
        if (isFrozen(claim)) {
            return claim.getSettledDue();
        }
        return LateCharge.withCharge(sheets.sheet(sheetId), claim, person.gradeOn(claim.getDate()), figure(sheetId, person, claim));
    }

    /**
     * A claim's due-by, fixed at creation: its date plus the first of the person's own terms, the
     * terms of their grade and the sheet's terms, each as in force on its date, counted in business
     * days: skipping every Saturday, Sunday and holiday on the sheet.
     */
    private LocalDate dueBy(String sheetId, Person person, LocalDate dated) {
        Integer own = person.getTerms();
        return holidays.businessDaysAfter(sheetId, dated,
                own != null ? own : sheets.sheet(sheetId).termsFor(person.gradeOn(dated), dated));
    }

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, List<String> tags) {
        roles.refuseViewer(sheetId, user);
        Person person = person(sheetId, personId);
        LocalDate dated = dateOrToday(sheetId, date);
        LocalDate dueBy = dueBy(sheetId, person, dated);
        periods.refuseIfClosed(sheetId, dueBy);
        refuseIfRetired(person, dated);
        String corrected = corrected(sheetId, personId, amount, corrects);
        refuseIfRefUsed(sheetId, ref, null);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = new Claim(sheetId, personId, amount, dated, dueBy, note, corrected);
        claim.setRef(ref);
        claim.setTags(tags);
        claim.setLateEntry(periods.isSoftClosed(sheetId, dueBy));
        logStatus(sheetId, user, person, claim);
        Claim added = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_ADDED", sheetId, user, person, added)
                + (added.getRef() != null ? " ref=" + added.getRef() : "")
                + (added.getTags().isEmpty() ? "" : " tags=" + String.join(",", added.getTags())));
        useTags(sheetId, added);
        if (added.isLateEntry()) {
            audit.recordOnCommit(record("LATE_ENTRY", sheetId, user, person, added));
        }
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, List<String> tags) {
        roles.refuseViewer(sheetId, user);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        refuseIfNotEditable(sheetId, user, claim);
        LocalDate dated = dateOrToday(sheetId, date);
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        refuseIfRetired(person(sheetId, claim.getPersonId()), dated);
        String corrected = corrected(sheetId, claim.getPersonId(), amount, corrects);
        refuseIfRefUsed(sheetId, ref, claim.getId());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(amount);
        claim.setRef(ref);
        claim.setTags(tags);
        claim.setDate(dated);
        claim.setNote(note);
        claim.setCorrects(corrected);
        claim.setLockedBy(null);
        claim.setEditedBy(user);
        Claim saved = claims.saveAndFlush(claim);
        useTags(sheetId, saved);
        audit.recordOnCommit(record("CLAIM_EDITED", sheetId, user, person(sheetId, saved.getPersonId()), saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Change a DRAFT claim's amount in its row, keeping its date, note and correction: refused
     * exactly as the claim form's save is, and recorded as CLAIM_EDITED as it is. An editor the
     * user has open on the claim stays open.
     */
    @Transactional
    public Claim updateAmount(String sheetId, String user, long id, BigDecimal amount) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        String lockedBy = claim.getLockedBy();
        Claim saved = update(sheetId, user, id, amount, claim.getDate(), claim.getNote(), claim.getCorrects(),
                claim.getRef(), claim.getTags());
        saved.setLockedBy(lockedBy);
        return claims.saveAndFlush(saved);
    }

    /**
     * Open a claim's editor: the claim is locked for every other user until the editor is saved or
     * cancelled, or the sheet's today changes. Refused (403) for a viewer, (409) for a claim that is
     * not DRAFT or is locked by another user, and (423) when dated inside a closed period; a refused
     * claim takes no lock. Any other claim of the same person the user had locked is released.
     */
    @Transactional
    public Claim lock(String sheetId, String user, long id) {
        roles.refuseViewer(sheetId, user);
        // hold the sheet so two users cannot both take the lock
        sheets.lock(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        refuseIfNotEditable(sheetId, user, claim);
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        for (Claim other : claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, claim.getPersonId())) {
            if (other.getId() != id && user.equals(other.getLockedBy())) {
                other.setLockedBy(null);
                claims.save(other);
            }
        }
        claim.setLockedBy(user);
        return claims.saveAndFlush(claim);
    }

    /** Cancel a claim's editor: the user's lock on the claim is released; another user's lock stays. */
    @Transactional
    public Claim unlock(String sheetId, String user, long id) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (user.equals(claim.getLockedBy())) {
            claim.setLockedBy(null);
            claim = claims.saveAndFlush(claim);
        }
        return claim;
    }

    /** Refuse (409) editing a claim that is not DRAFT, or that another user's open editor locks. */
    private static void refuseIfNotEditable(String sheetId, String user, Claim claim) {
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited.");
        }
        if (claim.isLockedAgainst(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This claim is being edited by " + claim.getLockedBy() + ".");
        }
    }

    /**
     * Move a claim to a new status, refused (409) when its current status does not allow it,
     * refused (403) when the user who opened it tries to settle it, and refused (422) settling a
     * claim above the proof threshold with no proofs. A clerk settling one whose
     * figure is above the sheet's clerk limit makes it PENDING instead.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next) {
        return changeStatus(sheetId, user, id, next, null, false, null);
    }

    /**
     * Void a claim, as {@link #changeStatus(String, String, long, ClaimStatus)}, with a reason from
     * the sheet's void reasons: while the sheet has any, a void without one of them is refused (422).
     */
    @Transactional
    public Claim voidClaim(String sheetId, String user, long id, String voidReason) {
        return changeStatus(sheetId, user, id, ClaimStatus.VOID, null, false, voidReason);
    }

    /**
     * Approve a PENDING claim: it becomes SETTLED, its due fixed at its live figure with its late
     * charge on the sheet's today. Refused (409) when not PENDING, and (403) for a clerk or the
     * user who opened it.
     */
    @Transactional
    public Claim approve(String sheetId, String user, long id) {
        return changeStatus(sheetId, user, id, ClaimStatus.SETTLED, null, true, null);
    }

    /**
     * Reject a PENDING claim with a reason: it is OPEN again, bound again by the last statement if
     * that statement included it. Refused (409) when not PENDING, and (403) for a clerk.
     */
    @Transactional
    public Claim reject(String sheetId, String user, long id, String reason) {
        return changeStatus(sheetId, user, id, ClaimStatus.OPEN, reason, true, null);
    }

    /** Move a claim to a new status; {@code review} for a steward's approval or rejection of a PENDING claim. */
    private Claim changeStatus(String sheetId, String user, long id, ClaimStatus next, String reason,
            boolean review, String voidReason) {
        roles.refuseViewer(sheetId, user);
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
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        boolean reversing = was == ClaimStatus.SETTLED;
        if (reversing) {
            refuseVoidSettled(sheetId, user, claim);
        }
        if (next == ClaimStatus.SETTLED && claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim deferred until " + claim.getDeferredUntil() + " cannot be settled before then.");
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot " + (review ? "approve" : "settle") + " it.");
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getEditedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who last edited a claim cannot " + (review ? "approve" : "settle") + " it.");
        }
        if (review && !isSteward(sheetId, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a steward can approve or reject a PENDING claim.");
        }
        String voidedWith = next == ClaimStatus.VOID ? voidReasons.refuseUnlessListed(sheetId, voidReason) : null;
        Person person = person(sheetId, claim.getPersonId());
        if (next == ClaimStatus.OPEN && !review && needsNote(sheetId, person, claim)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "A claim due above the note threshold cannot be opened with an empty note.");
        }
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
            claim.setNumber(sheets.nextClaimNumber(sheetId, claim.getDueBy()));
            claim.setOpenedBy(user);
        } else if (next == ClaimStatus.SETTLED) {
            claim.setSettledDue(settling);
        } else if (next == ClaimStatus.VOID) {
            claim.setVoidReason(voidedWith);
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
                + (reason != null ? " reason=" + reason : "")
                + (reversing ? " reversed=" + dueText(saved.getSettledDue()) : "")
                + (voidedWith != null ? " reason=" + voidedWith : ""));
        if (next == ClaimStatus.SETTLED) {
            notifyWatchers(sheetId, user, person, saved);
        }
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Refuse voiding a SETTLED claim: (403) for anyone but a steward, and (409) once the sheet's
     * today is more than {@value #VOID_SETTLED_DAYS} days after its settlement (or before it).
     */
    private void refuseVoidSettled(String sheetId, String user, Claim claim) {
        if (!isSteward(sheetId, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can void a SETTLED claim.");
        }
        LocalDate settled = claim.getLog().stream().filter(entry -> entry.getStatus() == ClaimStatus.SETTLED)
                .map(ClaimLogEntry::getDate).reduce((first, last) -> last).orElse(null);
        LocalDate today = sheets.today(sheetId);
        if (settled == null || today.isBefore(settled) || today.isAfter(settled.plusDays(VOID_SETTLED_DAYS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A SETTLED claim can be voided only within "
                    + VOID_SETTLED_DAYS + " days of its settlement.");
        }
    }

    /** Record a notice of the claim for each user watching its person, in the order they started watching. */
    private void notifyWatchers(String sheetId, String user, Person person, Claim claim) {
        for (Watch watch : watches.findByPersonIdOrderByIdAsc(person.getId())) {
            audit.recordOnCommit("NOTIFY sheet=" + sheetId + " by=" + user + " user=" + watch.getUserName()
                    + " person=" + person.getName() + " number=" + claim.getNumber());
        }
    }

    /**
     * Defer an OPEN claim until a date after the sheet's today: until the sheet's today reaches it
     * the claim counts in no total, is not overdue and cannot be settled; a statement it is bound
     * by stays as it is. Refused (409) for a claim that is not OPEN, (400) for a date
     * not after the sheet's today and (423) when dated inside a closed period.
     */
    @Transactional
    public Claim defer(String sheetId, String user, long id, LocalDate until) {
        roles.refuseViewer(sheetId, user);
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
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        Person person = person(sheetId, claim.getPersonId());
        claim.setDeferredUntil(until, sheets.today(sheetId));
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
        roles.refuseViewer(sheetId, user);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot have a proof added.");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        Person person = person(sheetId, claim.getPersonId());
        claim.addProof();
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_PROOF_ADDED", sheetId, user, person, saved)
                + (saved.getNumber() != null ? " number=" + saved.getNumber() : "") + " proofs=" + saved.getProofs());
        return saved;
    }

    /**
     * Comment on a claim in any status: kept with the user and the sheet's today, and written as
     * COMMENT_ADDED. Refused (422) for an empty comment, and as any change for a viewer or an
     * archived sheet.
     */
    @Transactional
    public Claim comment(String sheetId, String user, long id, String text) {
        roles.refuseViewer(sheetId, user);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (text.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A comment cannot be empty.");
        }
        Person person = person(sheetId, claim.getPersonId());
        claim.comment(new ClaimComment(user, sheets.today(sheetId), text));
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("COMMENT_ADDED", sheetId, user, person, saved)
                + (saved.getNumber() != null ? " number=" + saved.getNumber() : "") + " text=" + text);
        return saved;
    }

    /**
     * Remind an OPEN or PENDING claim: write REMIND and mark it reminded on the sheet's today.
     * Refused (409) for a claim in any other status or already reminded on the sheet's today.
     */
    @Transactional
    public Claim remind(String sheetId, String user, long id) {
        roles.refuseViewer(sheetId, user);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reminded.");
        }
        LocalDate today = sheets.today(sheetId);
        if (today.equals(claim.getRemindedOn())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This claim was already reminded today.");
        }
        Person person = person(sheetId, claim.getPersonId());
        claim.setRemindedOn(today);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("REMIND", sheetId, user, person, saved)
                + (saved.getNumber() != null ? " number=" + saved.getNumber() : ""));
        return saved;
    }

    /**
     * Re-settle a SETTLED claim: fix its due again at the figure it would have on the sheet's today
     * as an open claim bound by no statement, with its late charge. Refused (403) for anyone but a
     * steward, (409) for a claim that is not SETTLED and (423) when dated inside a closed period.
     */
    @Transactional
    public Claim resettle(String sheetId, String user, long id) {
        roles.refuseViewer(sheetId, user);
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
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal old = claim.getSettledDue();
        BigDecimal settling = LateCharge.withChargeAsOpen(sheets.sheet(sheetId), claim, person.gradeOn(claim.getDate()), due(sheetId, person, claim));
        claim.setSettledDue(settling);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_RESETTLED", sheetId, user, person, saved)
                + (saved.getNumber() != null ? " number=" + saved.getNumber() : "")
                + " old=" + dueText(old) + " new=" + dueText(settling));
        notifyWatchers(sheetId, user, person, saved);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Re-date an OPEN or PENDING claim. Its due-by stays as it was fixed, and with it its period and
     * number, and a figure bound by a statement stays bound. Refused (403) for anyone but a steward,
     * (409) for a claim that is not OPEN or PENDING, (423) when due inside a closed period and (422)
     * for a date after its person's retirement date.
     */
    @Transactional
    public Claim redate(String sheetId, String user, long id, LocalDate date) {
        roles.refuseViewer(sheetId, user);
        if (!isSteward(sheetId, user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can re-date a claim.");
        }
        // lock the sheet's numbering before reading the claim, as opening one does
        sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-dated.");
        }
        LocalDate old = claim.getDate();
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        Person person = person(sheetId, claim.getPersonId());
        refuseIfRetired(person, date);
        claim.setDate(date);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_REDATED", sheetId, user, person, saved)
                + (saved.getNumber() != null ? " number=" + saved.getNumber() : "")
                + " old=" + old + " new=" + date);
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
        periods.refuseIfClosed(sheetId, claim.getDueBy());
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

    /** What a split did: the original claim, keeping the rest of its amount, and the new claim split off it. */
    public record Split(Claim original, Claim split) {
    }

    /**
     * Split an amount off an OPEN claim: the claim keeps the rest of its amount and is unbound from
     * any statement; a new OPEN claim with the amount split off, the same date and note, the next
     * number, opened by the user and due by its date plus the terms in force on it, is listed first
     * under the person and shows the original's number as split-from. Refused (403) for a role that
     * may not open a claim, (409) for a claim that is not OPEN or is deferred, (400) for an amount
     * not above 0.00 and below the claim's, and (423) when the original is due inside a closed period
     * (the split's own due-by is not checked). The split is numbered in, and a late entry of, the
     * period of its own due-by.
     */
    @Transactional
    public Split split(String sheetId, String user, long id, BigDecimal amount) {
        roles.refuseViewer(sheetId, user);
        roles.refuseUnlessPermitted(sheetId, user, "open");
        // lock the sheet's numbering before reading the claim, as opening one does
        sheets.lock(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be split.");
        }
        if (claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim deferred until " + claim.getDeferredUntil() + " cannot be split before then.");
        }
        if (amount.signum() <= 0 || amount.compareTo(claim.getAmount()) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A split must be more than 0.00 and less than the claim's amount.");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        Person person = person(sheetId, claim.getPersonId());
        claim.setAmount(claim.getAmount().subtract(amount));
        claim.bind(false, null);
        Claim original = claims.saveAndFlush(claim);
        LocalDate dated = claim.getDate();
        Claim split = new Claim(sheetId, person.getId(), amount, dated,
                dueBy(sheetId, person, dated), claim.getNote(), null);
        split.setStatus(ClaimStatus.OPEN);
        split.setNumber(sheets.nextClaimNumber(sheetId, split.getDueBy()));
        split.setLateEntry(periods.isSoftClosed(sheetId, split.getDueBy()));
        split.setOpenedBy(user);
        split.setSplitFrom(original.getNumber());
        split.setTags(original.getTags());
        logStatus(sheetId, user, person, split);
        Claim added = claims.saveAndFlush(split);
        audit.recordOnCommit(record("CLAIM_SPLIT", sheetId, user, person, original)
                + " number=" + original.getNumber() + " into=" + added.getNumber());
        audit.recordOnCommit(record("CLAIM_OPENED", sheetId, user, person, added) + " number=" + added.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return new Split(original, added);
    }

    /** What a bulk open did: how many claims were opened and how many refused, and the claims opened. */
    public record BulkOpened(int opened, int refused, List<Claim> claims) {
    }

    /**
     * Open the given claims together, in list order (the order people were added, newest claim
     * first within a person), so they are numbered in that order. A claim that cannot be opened
     * (not on the sheet, not DRAFT, dated inside a closed period, or due above the note threshold
     * with an empty note) is refused and left as it is.
     */
    @Transactional
    public BulkOpened bulkOpen(String sheetId, String user, Collection<Long> ids) {
        List<Claim> selected = inListOrder(sheetId, ids);
        int refused = (int) ids.stream().distinct().count() - selected.size();
        List<Claim> opened = new ArrayList<>();
        boolean permitted = sheets.sheet(sheetId).permits("open", roles.roleOf(sheetId, user));
        for (Claim claim : selected) {
            if (!permitted || claim.getStatus() != ClaimStatus.DRAFT || periods.isClosed(sheetId, claim.getDueBy())
                    || needsNote(sheetId, person(sheetId, claim.getPersonId()), claim)) {
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
     * rounded to the cent as the claim rounds (see {@link Rounding}), not raised to the floor (see {@link LateCharge});
     * null with no rate in force then.
     */
    public BigDecimal due(String sheetId, Person person, Claim claim) {
        Sheet sheet = sheets.sheet(sheetId);
        RoundingMode rounding = Rounding.mode(sheet, roundingDate(sheetId, claim));
        return rates.findInForce(sheetId, person.gradeOn(claim.getDate()), claim.getDate()).map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2, rounding))
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

    /** Whether a claim's due is above the note threshold in force on its date and its note is empty. */
    private boolean needsNote(String sheetId, Person person, Claim claim) {
        if (claim.getNote() != null && !claim.getNote().isBlank()) {
            return false;
        }
        BigDecimal threshold = sheets.sheet(sheetId).noteThresholdOn(claim.getDate());
        BigDecimal due = threshold == null ? null : charged(sheetId, person, claim);
        return due != null && due.compareTo(threshold) > 0;
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

    /** Refuse a reference (none when null) another claim on the sheet already has, without regard to case. */
    void refuseIfRefUsed(String sheetId, String ref, Long exceptId) {
        if (ref != null && (exceptId == null ? claims.existsBySheetIdAndRefIgnoreCase(sheetId, ref)
                : claims.existsBySheetIdAndRefIgnoreCaseAndIdNot(sheetId, ref, exceptId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The reference " + ref + " is already used on this sheet.");
        }
    }

    /** Every tag ever used on the sheet, sorted. */
    @Transactional(readOnly = true)
    public List<String> usedTags(String sheetId) {
        return usedTags.findBySheetIdOrderByTagAsc(sheetId).stream().map(UsedTag::getTag).toList();
    }

    /** Keep each of the claim's tags as used on the sheet. */
    private void useTags(String sheetId, Claim claim) {
        for (String tag : claim.getTags()) {
            if (!usedTags.existsBySheetIdAndTag(sheetId, tag)) {
                usedTags.saveAndFlush(new UsedTag(sheetId, tag));
            }
        }
    }

    private static String record(String event, String sheetId, String user, Person person, Claim claim) {
        return event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName() + " amount="
                + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString() + " date=" + claim.getDate();
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.entryDate(sheetId);
    }
}
