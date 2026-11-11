package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.WatchService;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.voidreason.VoidReason;
import net.officefloor.hq.app.voidreason.VoidReasonService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the people's claims on a sheet. A new claim with no date is dated the sheet's entry date while it is set, else
 * the sheet's today; an edited one with no date the sheet's today. A claim belongs to the period its due-by (fixed when
 * it is added) falls in; one whose due-by is in a closed period cannot be added, edited, deferred or change status. Only a DRAFT claim can be edited; a PENDING claim, waiting for a
 * steward to approve or reject it, is otherwise handled like an OPEN one. No claim
 * can be dated after the retirement date of its person. A claim whose due is above the proof threshold on its date
 * cannot be settled or approved without a proof; one whose due is above the note threshold on its date cannot be
 * opened with an empty note. A claim with a negative amount is a correction: it must name,
 * by number, a SETTLED claim of the same person that it corrects, and its due has no floor and no late charge.
 */
@Service
public class ClaimService {

    /** The days after its settlement within which a steward may still void a SETTLED claim. */
    private static final int SETTLED_VOID_DAYS = 7;

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateService rates;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final PeriodService periods;
    private final RoleService roles;
    private final SettingService settings;
    private final ClaimLogRepository logs;
    private final WatchService watches;
    private final HolidayService holidays;
    private final SheetTagRepository tags;
    private final VoidReasonService voidReasons;
    private final ClaimCommentRepository comments;
    private final ClaimLineRepository lines;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateService rates, SheetService sheets,
            Audit audit, RollupService rollup, PeriodService periods, RoleService roles, SettingService settings,
            ClaimLogRepository logs, WatchService watches, HolidayService holidays, SheetTagRepository tags,
            VoidReasonService voidReasons, ClaimCommentRepository comments, ClaimLineRepository lines) {
        this.lines = lines;
        this.comments = comments;
        this.voidReasons = voidReasons;
        this.tags = tags;
        this.holidays = holidays;
        this.logs = logs;
        this.watches = watches;
        this.periods = periods;
        this.roles = roles;
        this.settings = settings;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
    }

    /** Every tag ever used on the sheet, sorted. */
    public List<String> tags(String sheetId) {
        return tags.findBySheetIdOrderByTagAsc(sheetId).stream().map(SheetTag::getTag).toList();
    }

    /** The status logs of the sheet's claims by claim id, each oldest first. */
    public Map<Long, List<ClaimLog>> logs(String sheetId) {
        return logs.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(ClaimLog::getClaimId, Collectors.toList()));
    }

    /** The claim's status log, oldest first. */
    public List<ClaimLog> logOf(Claim claim) {
        return logs.findByClaimIdOrderByIdAsc(claim.getId());
    }

    /** The comments on the sheet's claims by claim id, each oldest first. */
    public Map<Long, List<ClaimComment>> comments(String sheetId) {
        return comments.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(ClaimComment::getClaimId, Collectors.toList()));
    }

    /** The claim's comments, oldest first. */
    public List<ClaimComment> commentsOf(Claim claim) {
        return comments.findByClaimIdOrderByIdAsc(claim.getId());
    }

    /**
     * Comment a claim of any status: the comment keeps the user and the sheet's today. Refused for an empty comment.
     */
    @Transactional
    public Claim comment(String sheetId, String user, Long id, String text) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        String written = text == null ? "" : text.trim();
        if (written.isEmpty()) {
            throw new ClaimCommentException(id, "a comment cannot be empty");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        comments.save(new ClaimComment(sheetId, id, user, sheets.today(sheetId), written));
        audit.recordOnCommit(record("COMMENT_ADDED", user, person, claim) + numberOf(claim.getNumber())
                + " text=" + written);
        return claim;
    }

    /** The lines of the sheet's claims by claim id, each oldest first. */
    public Map<Long, List<ClaimLine>> lines(String sheetId) {
        return lines.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(ClaimLine::getClaimId, Collectors.toList()));
    }

    /** The claim's lines, oldest first. */
    public List<ClaimLine> linesOf(Claim claim) {
        return lines.findByClaimIdOrderByIdAsc(claim.getId());
    }

    /**
     * Add a line to a claim, which counts as editing it: refused, like an edit, for a claim that is not a DRAFT, due
     * by a date in a closed period or locked by another user, and for an empty label. The claim's amount becomes its
     * lines' quantities times their unit amounts added up.
     */
    @Transactional
    public Claim addLine(String sheetId, String user, Long id, String label, int qty, BigDecimal unit) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotEditableException(id, claim.getStatus());
        }
        if (claim.isLockedAgainst(user)) {
            throw new ClaimLockedException(id, claim.getLockedBy());
        }
        periods.requireOpen(sheetId, claim.getDueBy());
        String labelled = label == null ? "" : label.trim();
        if (labelled.isEmpty()) {
            throw new ClaimLineException(id, "a line needs a label");
        }
        BigDecimal amount = unit.multiply(BigDecimal.valueOf(qty));
        for (ClaimLine line : lines.findByClaimIdOrderByIdAsc(id)) {
            amount = amount.add(line.total());
        }
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        if (amount.precision() - amount.scale() > 10) {
            throw new ClaimLineException(id, "its lines add up to more than a claim can hold");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        lines.save(new ClaimLine(sheetId, id, labelled, qty, unit));
        claim.setAmount(amount);
        claim.setEditedBy(user);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_LINE_ADDED", user, person, saved) + " label=" + labelled + " qty=" + qty
                + " unit=" + unit.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        return add(sheetId, user, personId, amount, date, note, corrects, null);
    }

    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref) {
        return add(sheetId, user, personId, amount, date, note, corrects, ref, null);
    }

    /**
     * Adds a claim with an external reference (blank: none), refused when the reference is already used on the sheet,
     * and comma separated tags (blank: none).
     */
    @Transactional
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tagText) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, personId));
        LocalDate dated = date != null ? date : sheets.entryDate(sheetId);
        // Due by its person's terms on its date in business days after it, skipping the sheet's holidays now, fixed from now on;
        // its period is the one of its due-by.
        int terms = termsFor(sheetId, person, dated);
        LocalDate dueBy = holidays.businessDaysAfter(sheetId, dated, terms);
        periods.requireOpen(sheetId, dueBy);
        person.requireClaimable(dated);
        LocalDate correctedDate = requireCorrection(sheetId, personId, amount, corrects);
        Claim claim = new Claim(sheetId, personId, amount, dated, noteOf(note));
        claim.setCorrects(corrects);
        claim.setRoundingDate(correctedDate != null ? correctedDate : dated);
        claim.setDueBy(dueBy);
        claim.setTerms(terms);
        // Taking the next list place locks the sheet, so two adds of the same reference do not both pass the check.
        claim.setListed(sheets.nextListed(sheetId));
        String reference = refOf(ref);
        if (reference != null && claims.existsBySheetIdAndRefIgnoreCase(sheetId, reference)) {
            throw new ClaimRefException(reference);
        }
        claim.setRef(reference);
        claim.setTags(tagged(sheetId, tagText));
        claim.setLateEntry(periods.softClosedOn(sheetId, dueBy));
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_ADDED", user, person, saved)
                + (saved.getRef() == null ? "" : " ref=" + saved.getRef())
                + (saved.getTags() == null ? "" : " tags=" + saved.getTags()));
        if (saved.isLateEntry()) {
            audit.recordOnCommit(record("LATE_ENTRY", user, person, saved));
        }
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    @Transactional
    public Claim update(String sheetId, String user, Long id, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tagText) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        // Only a DRAFT can be edited: an OPEN claim is changed by voiding it and adding a new one.
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotEditableException(id, claim.getStatus());
        }
        // Not while another user has its editor open.
        if (claim.isLockedAgainst(user)) {
            throw new ClaimLockedException(id, claim.getLockedBy());
        }
        LocalDate dated = dateOr(sheetId, date);
        // Its due-by, and so its period, stays as fixed when it was added.
        periods.requireOpen(sheetId, claim.getDueBy());
        // A claim with lines keeps their sum as its amount.
        if (amount.compareTo(claim.getAmount()) != 0 && lines.existsByClaimId(id)) {
            throw new ClaimAmountFromLinesException(id);
        }
        people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()))
                .requireClaimable(dated);
        LocalDate correctedDate = requireCorrection(sheetId, claim.getPersonId(), amount, corrects);
        String reference = refOf(ref);
        if (reference != null && claims.existsBySheetIdAndRefIgnoreCaseAndIdNot(sheetId, reference, id)) {
            throw new ClaimRefException(reference);
        }
        claim.setRef(reference);
        claim.setTags(tagged(sheetId, tagText));
        claim.setAmount(amount);
        claim.setCorrects(corrects);
        claim.setDate(dated);
        claim.setRoundingDate(correctedDate != null ? correctedDate : dated);
        claim.setNote(noteOf(note));
        claim.setEditedBy(user);
        // Saving the editor releases its lock.
        claim.setLockedBy(null);
        Claim saved = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(saved.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, saved.getPersonId()));
        audit.recordOnCommit(record("CLAIM_EDITED", user, person, saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Open the claim's editor for the user, locking the claim against other users' edits until it is saved or
     * cancelled, or the sheet's today changes. Refused, taking no lock, for a claim that cannot be edited: not a DRAFT,
     * due by a date in a closed period, or locked by another user. The user's own lock is taken again.
     */
    @Transactional
    public Claim lock(String sheetId, String user, Long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotEditableException(id, claim.getStatus());
        }
        periods.requireOpen(sheetId, claim.getDueBy());
        if (claim.isLockedAgainst(user)) {
            throw new ClaimLockedException(id, claim.getLockedBy());
        }
        claim.setLockedBy(user);
        return claims.saveAndFlush(claim);
    }

    /** Cancel the user's editor of the claim, releasing its lock; another user's lock is left as it is. */
    @Transactional
    public Claim unlock(String sheetId, String user, Long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (user.equals(claim.getLockedBy())) {
            claim.setLockedBy(null);
            return claims.saveAndFlush(claim);
        }
        return claim;
    }

    /**
     * Move a claim to another status, if its current one allows it; refused otherwise. A clerk settling a claim whose
     * due is above the clerk limit sends it to PENDING instead, for a steward to approve or reject.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, Long id, ClaimStatus status) {
        return changeStatus(sheetId, user, id, status, null);
    }

    /**
     * Move a claim to another status, as {@link #changeStatus(String, String, Long, ClaimStatus)}. While the sheet's
     * list of void reasons is not empty, a void needs a reason from it; the claim keeps the reason it was voided with.
     */
    @Transactional
    public Claim changeStatus(String sheetId, String user, Long id, ClaimStatus status, String voidReason) {
        return changeStatus(sheetId, user, id, status, voidReason, null);
    }

    /**
     * Settle a claim as part of a bulk settle, as {@link #changeStatus(String, String, Long, ClaimStatus)}: once it is
     * SETTLED or PENDING it carries the batch number the supplier gives, asked for only then, within the same change.
     */
    @Transactional
    public Claim settleInBatch(String sheetId, String user, Long id, LongSupplier batch) {
        return changeStatus(sheetId, user, id, ClaimStatus.SETTLED, null, batch);
    }

    private Claim changeStatus(String sheetId, String user, Long id, ClaimStatus status, String voidReason,
            LongSupplier batch) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        boolean voidsSettled = status == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED;
        if (!voidsSettled && (!claim.getStatus().canMoveTo(status) || status == ClaimStatus.PENDING)) {
            throw new ClaimStatusException(id, claim.getStatus(), status);
        }
        // The sheet's permission matrix decides which roles may open, settle or void.
        String action = switch (status) {
            case OPEN -> "open";
            case SETTLED -> "settle";
            case VOID -> "void";
            case DRAFT, PENDING, DELETED -> throw new ClaimStatusException(id, claim.getStatus(), status);
        };
        if (!roles.may(sheetId, user, action)) {
            throw new ClaimActionForbiddenException(id, user, action);
        }
        if (voidsSettled) {
            requireSettledVoidable(sheetId, user, claim);
        }
        periods.requireOpen(sheetId, claim.getDueBy());
        String reason = status == ClaimStatus.VOID ? requireVoidReason(sheetId, id, voidReason) : null;
        // A deferred claim cannot be settled until the sheet's today reaches the date it is deferred until.
        if (status == ClaimStatus.SETTLED && claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ClaimDeferException(id, "it is deferred until " + claim.getDeferredUntil());
        }
        // Four eyes: whoever opened or last edited a claim cannot also settle it.
        if (status == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ClaimSettledByOpenerException(id, user, "opened");
        }
        if (status == ClaimStatus.SETTLED && user.equals(claim.getEditedBy())) {
            throw new ClaimSettledByOpenerException(id, user, "last edited");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        if (status == ClaimStatus.SETTLED) {
            requireProof(sheetId, person, claim);
        }
        if (status == ClaimStatus.OPEN) {
            requireNote(sheetId, person, claim);
        }
        // A clerk settles only a claim whose due (the one its statement bound it to, else worked out from the rate)
        // is at or below the clerk limit; one above it goes to PENDING. A due of n/a is within any limit.
        if (status == ClaimStatus.SETTLED && roles.roleOf(sheetId, user) == Role.CLERK) {
            BigDecimal limit = settings.get(sheetId).getClerkLimit();
            BigDecimal due = liveDue(sheetId, person, claim, figureOf(sheetId, person, claim));
            if (limit != null && due != null && due.compareTo(limit) > 0) {
                status = ClaimStatus.PENDING;
            }
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String due = status == ClaimStatus.SETTLED ? settle(sheetId, person, claim)
                : voidsSettled ? " reversed=" + shownDue(sheetId, person, claim) : "";
        claim.setStatus(status);
        if (status == ClaimStatus.VOID) {
            claim.setVoidReason(reason);
        }
        // Settled one at a time, a claim carries no batch; settled by a bulk settle, it carries that batch's number.
        if (status == ClaimStatus.SETTLED || status == ClaimStatus.PENDING) {
            claim.setSettleBatch(batch == null ? null : batch.getAsLong());
        }
        // A voided claim keeps its number (never reused), and the CLAIM_VOIDED record carries it.
        String number = claim.getNumber();
        if (status == ClaimStatus.OPEN) {
            number = nextNumber(sheetId, claim.getDueBy());
            claim.setNumber(number);
            claim.setOpenedBy(user);
        }
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        String event = switch (status) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT, DELETED -> throw new IllegalStateException();
        };
        audit.recordOnCommit(record(event, user, person, saved) + numberOf(number) + due
                + (reason == null ? "" : " reason=" + reason));
        if (status == ClaimStatus.SETTLED) {
            watches.notifyWatchers(sheetId, user, person, number);
        }
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Delete a DRAFT claim: it becomes DELETED, counts nowhere and cannot be edited, opened or voided until it is
     * restored. Refused for any other status, a claim due by a date in a closed period, or one another user's open
     * editor locks.
     */
    @Transactional
    public Claim delete(String sheetId, String user, Long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimStatusException(id, claim.getStatus(), ClaimStatus.DELETED);
        }
        if (claim.isLockedAgainst(user)) {
            throw new ClaimLockedException(id, claim.getLockedBy());
        }
        periods.requireOpen(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.DELETED);
        claim.setLockedBy(null);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_DELETED", user, person, saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Restore a DELETED claim to DRAFT, only on the sheet's today it was deleted (the date of the last DELETED line of
     * its status log). Refused for any other status, a later day, or a claim due by a date in a closed period.
     */
    @Transactional
    public Claim restore(String sheetId, String user, Long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (claim.getStatus() != ClaimStatus.DELETED) {
            throw new ClaimStatusException(id, claim.getStatus(), ClaimStatus.DRAFT);
        }
        LocalDate deletedOn = logOf(claim).stream()
                .filter(l -> l.getStatus() == ClaimStatus.DELETED)
                .map(ClaimLog::getDate)
                .reduce((first, last) -> last)
                .orElse(null);
        LocalDate today = sheets.today(sheetId);
        if (!today.equals(deletedOn)) {
            throw new ClaimRestoreException(id, "a deleted claim can only be restored on the day it was deleted, "
                    + deletedOn);
        }
        periods.requireOpen(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.DRAFT);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_RESTORED", user, person, saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * The reason a claim is voided with: none while the sheet's list of void reasons is empty, else the one given,
     * which must be on the list.
     */
    private String requireVoidReason(String sheetId, Long id, String given) {
        List<String> listed = voidReasons.list(sheetId).stream().map(VoidReason::getReason).toList();
        if (listed.isEmpty()) {
            return null;
        }
        String reason = given == null ? "" : given.trim();
        if (reason.isEmpty()) {
            throw new ClaimVoidReasonException(id, "choose a reason from the sheet's void reasons");
        }
        if (!listed.contains(reason)) {
            throw new ClaimVoidReasonException(id, reason + " is not one of the sheet's void reasons");
        }
        return reason;
    }

    /**
     * A steward other than its opener or last editor approves a PENDING claim: it becomes SETTLED, its due frozen at that moment from
     * its live figure.
     */
    @Transactional
    public Claim approve(String sheetId, String user, Long id) {
        Claim claim = pendingForDecision(sheetId, user, id, ClaimStatus.SETTLED);
        if (user.equals(claim.getOpenedBy())) {
            throw new ClaimApprovalForbiddenException(id, user, "whoever opened a claim cannot approve it");
        }
        if (user.equals(claim.getEditedBy())) {
            throw new ClaimApprovalForbiddenException(id, user, "whoever last edited a claim cannot approve it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        requireProof(sheetId, person, claim);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String due = settle(sheetId, person, claim);
        claim.setStatus(ClaimStatus.SETTLED);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_APPROVED", user, person, saved) + numberOf(saved.getNumber()) + due);
        watches.notifyWatchers(sheetId, user, person, saved.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward rejects a PENDING claim with a reason: it goes back to OPEN, bound again by its person's last statement
     * if that statement included it.
     */
    @Transactional
    public Claim reject(String sheetId, String user, Long id, String reason) {
        Claim claim = pendingForDecision(sheetId, user, id, ClaimStatus.OPEN);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        // Back to OPEN, it is no longer settled by the batch that sent it to PENDING.
        claim.setSettleBatch(null);
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_REJECTED", user, person, saved) + numberOf(saved.getNumber())
                + " reason=" + reason);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A user whose role may reassign moves a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its date and,
     * unless a draft, takes the next number in the sequence it is numbered in (its old one is never used again), loses any figure a statement bound it to (live again until its new person's next statement), and is listed as
     * its new person's newest claim. Refused for a SETTLED or VOID claim, its own person, a closed period, a new person
     * retired before its date, or a user whose role may not reassign.
     */
    @Transactional
    public Claim reassign(String sheetId, String user, Long id, Long toPersonId) {
        return reassign(sheetId, user, id, toPersonId, false);
    }

    /**
     * Reassign as {@link #reassign(String, String, Long, Long)}; with {@code toOwnAllowed} (a bulk reassign) a claim
     * already the person's is moved too, listed again as its newest claim.
     */
    @Transactional
    public Claim reassign(String sheetId, String user, Long id, Long toPersonId, boolean toOwnAllowed) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (!roles.may(sheetId, user, "reassign")) {
            throw new ClaimReassignForbiddenException(id, user);
        }
        if (claim.getStatus() != ClaimStatus.DRAFT && !claim.getStatus().isOpen()) {
            throw new ClaimReassignException(id, "it is " + claim.getStatus());
        }
        if (!toOwnAllowed && claim.getPersonId().equals(toPersonId)) {
            throw new ClaimReassignException(id, "it is already that person's");
        }
        periods.requireOpen(sheetId, claim.getDueBy());
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        Person to = people.findByIdAndSheetId(toPersonId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, toPersonId));
        to.requireClaimable(claim.getDate());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setPersonId(to.getId());
        claim.setStated(false, null);
        claim.setListed(sheets.nextListed(sheetId));
        if (claim.getNumber() != null) {
            // A moved claim takes the next number in its sequence; its old one is never used again.
            claim.setNumber(nextNumber(sheetId, claim.getDueBy()));
        }
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_REASSIGNED", user, to, saved) + numberOf(saved.getNumber())
                + " from=" + from.getName() + " to=" + to.getName());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Defer an OPEN claim until a date after the sheet's today: until the sheet's today reaches it the claim counts in
     * no total, is not overdue, cannot be settled and is left unbound by a statement. Refused for a claim not OPEN, a
     * date not after today, or a closed period.
     */
    @Transactional
    public Claim defer(String sheetId, String user, Long id, LocalDate until) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ClaimDeferException(id, "it is " + claim.getStatus() + ", only an OPEN claim can be deferred");
        }
        LocalDate today = sheets.today(sheetId);
        if (until == null || !until.isAfter(today)) {
            throw new ClaimDeferException(id, "it can only be deferred to a date after " + today);
        }
        periods.requireOpen(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setDeferredUntil(until);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_DEFERRED", user, person, saved) + numberOf(saved.getNumber())
                + " until=" + until);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward re-settles a SETTLED claim: its due is frozen again at the figure it would have today as an OPEN claim
     * bound by no statement (today's rates and grades with its late charge as of today, raised to the floor). Refused for any other
     * status, a closed period, or a user not a steward.
     */
    @Transactional
    public Claim resettle(String sheetId, String user, Long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ClaimResettleForbiddenException(id, user);
        }
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ClaimStatusException(id, claim.getStatus(), ClaimStatus.SETTLED);
        }
        periods.requireOpen(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String old = shownDue(sheetId, person, claim);
        BigDecimal figure = dueOf(sheetId, person, claim);
        Sheet sheet = settings.get(sheetId);
        claim.setSettledDue(claim.isCorrection() ? figure
                : sheet.floored(sheet.withLateCharge(figure, true, overdueBy(sheet, person, claim), claim.getDate()), claim.getDate()));
        Claim saved = claims.saveAndFlush(claim);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_RESETTLED", user, person, saved) + numberOf(saved.getNumber())
                + " old=" + old + " new=" + shownDue(sheetId, person, saved));
        watches.notifyWatchers(sheetId, user, person, saved.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * A steward re-dates an OPEN or PENDING claim. Its due-by stays as it was fixed, and so its period and number, and a
     * figure its statement bound it to stays bound. Refused for any other status, a date after its person's retirement,
     * a claim due by a date in a closed period, or a user not a steward.
     */
    @Transactional
    public Claim redate(String sheetId, String user, Long id, LocalDate date) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ClaimRedateForbiddenException(id, user);
        }
        if (!claim.getStatus().isOpen()) {
            throw new ClaimRedateException(id, "it is " + claim.getStatus()
                    + ", only an OPEN or PENDING claim can be re-dated");
        }
        LocalDate old = claim.getDate();
        // Its due-by, and so its period, stays as fixed.
        periods.requireOpen(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        person.requireClaimable(date);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setDate(date);
        if (!claim.isCorrection()) {
            claim.setRoundingDate(date);
        }
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_REDATED", user, person, saved) + numberOf(saved.getNumber())
                + " old=" + old + " new=" + date);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Split an amount (more than 0.00 and less than its own) off an OPEN claim not deferred. The claim keeps the rest
     * and loses any figure a statement bound it to; a new OPEN claim of the same person with the amount split off, the
     * same date and note, the next number, opened by the user, due by its date plus the terms in force on that date,
     * names the claim's number as the one it was split from. Refused for any other status, a claim due by a date in a
     * closed period (the new claim's own due-by is not weighed), or a user
     * whose role may not open a claim.
     */
    @Transactional
    public Claim split(String sheetId, String user, Long id, BigDecimal amount) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (!roles.may(sheetId, user, "open")) {
            throw new ClaimActionForbiddenException(id, user, "open");
        }
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ClaimSplitException(id, "it is " + claim.getStatus() + ", only an OPEN claim can be split");
        }
        if (claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ClaimSplitException(id, "it is deferred until " + claim.getDeferredUntil());
        }
        if (amount.signum() <= 0 || amount.compareTo(claim.getAmount()) >= 0) {
            throw new ClaimSplitException(id, "the amount split off must be more than 0.00 and less than "
                    + claim.getAmount().toPlainString());
        }
        periods.requireOpen(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(claim.getAmount().subtract(amount));
        claim.setStated(false, null);
        Claim kept = claims.saveAndFlush(claim);
        Claim part = new Claim(sheetId, kept.getPersonId(), amount, kept.getDate(), kept.getNote());
        part.setRoundingDate(kept.getRoundingDate());
        part.setTerms(termsFor(sheetId, person, kept.getDate()));
        part.setDueBy(holidays.businessDaysAfter(sheetId, kept.getDate(), part.getTerms()));
        part.setListed(sheets.nextListed(sheetId));
        part.setLateEntry(periods.softClosedOn(sheetId, part.getDueBy()));
        part.setStatus(ClaimStatus.OPEN);
        String number = nextNumber(sheetId, part.getDueBy());
        part.setNumber(number);
        part.setOpenedBy(user);
        part.setSplitFrom(kept.getNumber());
        part.setTags(kept.getTags());
        Claim saved = claims.saveAndFlush(part);
        log(sheetId, user, person, saved);
        audit.recordOnCommit(record("CLAIM_SPLIT", user, person, kept) + numberOf(kept.getNumber())
                + " into=" + number);
        audit.recordOnCommit(record("CLAIM_OPENED", user, person, saved) + numberOf(number));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Add a proof to a claim, raising its proofs count by one. Refused for a SETTLED, VOID or DELETED claim, or a closed
     * period.
     */
    @Transactional
    public Claim addProof(String sheetId, String user, Long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                || claim.getStatus() == ClaimStatus.DELETED) {
            throw new ClaimProofException(id, "it is " + claim.getStatus() + ", a proof cannot be added");
        }
        periods.requireOpen(sheetId, claim.getDueBy());
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        claim.addProof();
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_PROOF_ADDED", user, person, saved) + numberOf(saved.getNumber())
                + " proofs=" + saved.getProofs());
        return saved;
    }

    /**
     * Remind an OPEN or PENDING claim: REMIND is written and the claim's reminded date becomes the sheet's today.
     * Refused for any other status, and for a claim already reminded on the sheet's today.
     */
    @Transactional
    public Claim remind(String sheetId, String user, Long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING) {
            throw new ClaimRemindException(id, "it is " + claim.getStatus()
                    + ", only an OPEN or PENDING claim can be reminded");
        }
        LocalDate today = sheets.today(sheetId);
        if (today.equals(claim.getRemindedOn())) {
            throw new ClaimRemindException(id, "it was already reminded on " + today);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, claim.getPersonId()));
        claim.setRemindedOn(today);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("REMIND", user, person, saved) + numberOf(saved.getNumber()));
        return saved;
    }

    /**
     * A claim whose due (as the clerk limit weighs it) is above the proof threshold in force on its date (its person's
     * grade's own then, else the sheet's) cannot be settled or approved while it has no proof; refused then. A due of
     * n/a is within any threshold.
     */
    private void requireProof(String sheetId, Person person, Claim claim) {
        BigDecimal threshold = settings.get(sheetId).proofThresholdFor(person.gradeOn(claim.getDate()), claim.getDate());
        if (threshold == null || claim.getProofs() > 0) {
            return;
        }
        BigDecimal due = liveDue(sheetId, person, claim, figureOf(sheetId, person, claim));
        if (due != null && due.compareTo(threshold) > 0) {
            throw new ClaimProofException(claim.getId(), "its due " + due.toPlainString()
                    + " is above the proof threshold " + threshold.toPlainString() + " and it has no proof");
        }
    }

    /**
     * A claim whose due is above the note threshold in force on its date cannot be opened while its note is empty;
     * refused then. A due of n/a is within any threshold.
     */
    private void requireNote(String sheetId, Person person, Claim claim) {
        BigDecimal threshold = settings.get(sheetId).noteThresholdOn(claim.getDate());
        if (threshold == null || !noteOf(claim.getNote()).isEmpty()) {
            return;
        }
        BigDecimal due = liveDue(sheetId, person, claim, figureOf(sheetId, person, claim));
        if (due != null && due.compareTo(threshold) > 0) {
            throw new ClaimNoteException(claim.getId(), "its due " + due.toPlainString()
                    + " is above the note threshold " + threshold.toPlainString() + " and its note is empty");
        }
    }

    /**
     * Only a steward voids a SETTLED claim, and only within the days allowed after its settlement (the date of the last
     * SETTLED line of its status log) by the sheet's today; refused otherwise.
     */
    private void requireSettledVoidable(String sheetId, String user, Claim claim) {
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ClaimActionForbiddenException(claim.getId(), user, "void a settled claim");
        }
        LocalDate settledOn = logOf(claim).stream()
                .filter(l -> l.getStatus() == ClaimStatus.SETTLED)
                .map(ClaimLog::getDate)
                .reduce((first, last) -> last)
                .orElse(null);
        LocalDate today = sheets.today(sheetId);
        if (settledOn == null || today.isAfter(settledOn.plusDays(SETTLED_VOID_DAYS))) {
            throw new ClaimVoidException(claim.getId(), "a settled claim can only be voided within "
                    + SETTLED_VOID_DAYS + " days of its settlement on " + settledOn);
        }
    }

    /** The PENDING claim a steward decides; refused for any other status, a closed period, or a user not a steward. */
    private Claim pendingForDecision(String sheetId, String user, Long id, ClaimStatus to) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ClaimStatusException(id, claim.getStatus(), to);
        }
        periods.requireOpen(sheetId, claim.getDueBy());
        if (roles.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ClaimApprovalForbiddenException(id, user, "only a steward approves or rejects a pending claim");
        }
        return claim;
    }

    /**
     * Fixes the due the claim shows as it settles: its figure (the one its statement binds it to, else worked out from
     * the rate on the claim's date) with its late charge, raised to the floor on its date, frozen in (a correction
     * freezes its bare figure); with no figure it stays live. Gives the due at the
     * end of its audit record.
     */
    private String settle(String sheetId, Person person, Claim claim) {
        BigDecimal figure = figureOf(sheetId, person, claim);
        Sheet sheet = settings.get(sheetId);
        BigDecimal settledDue = figure == null || claim.isCorrection() ? figure
                : sheet.floored(figure.add(sheet.lateCharge(figure, overdueBy(sheet, person, claim), claim.getDate())), claim.getDate());
        claim.setSettledDue(settledDue);
        return " due=" + (settledDue == null ? "n/a" : settledDue.toPlainString());
    }

    /** Logs the claim's status now: who changed it, the sheet's today, and its due as computed at this moment. */
    private void log(String sheetId, String user, Person person, Claim claim) {
        logs.save(new ClaimLog(sheetId, claim.getId(), claim.getStatus(), user, sheets.today(sheetId),
                shownDue(sheetId, person, claim)));
    }

    /**
     * The claim's due as shown: the one fixed when it was settled, else its live due; two decimals, or n/a.
     */
    private String shownDue(String sheetId, Person person, Claim claim) {
        BigDecimal due = claim.getSettledDue() != null ? claim.getSettledDue()
                : liveDue(sheetId, person, claim, figureOf(sheetId, person, claim));
        return due == null ? "n/a" : due.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    /** The figure under the claim's due: the one its statement binds it to now, else worked out from the rate. */
    private BigDecimal figureOf(String sheetId, Person person, Claim claim) {
        return claim.isBound() ? claim.getStatedDue() : dueOf(sheetId, person, claim);
    }

    /**
     * A negative amount (a correction) must name, by number, a SETTLED claim of the same person; any other amount
     * names none. Refused otherwise. Gives the date of the claim a correction corrects (it rounds by that date), null
     * for any other claim.
     */
    private LocalDate requireCorrection(String sheetId, Long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new ClaimCorrectionException("Only a correction (a negative amount) corrects a claim");
            }
            return null;
        }
        if (corrects == null) {
            throw new ClaimCorrectionException("A negative amount must name the settled claim it corrects");
        }
        return claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .filter(c -> c.getStatus() == ClaimStatus.SETTLED)
                .map(Claim::getDate)
                .orElseThrow(() -> new ClaimCorrectionException(
                        "Claim " + corrects + " is not a settled claim of the same person"));
    }

    /**
     * The claim's amount at the rate its person's grade has on its date, two decimals rounded as the sheet's policy
     * date has the claim round (the rate figure, with no floor); null (n/a) with no rate.
     */
    private BigDecimal dueOf(String sheetId, Person person, Claim claim) {
        Sheet sheet = settings.get(sheetId);
        return rates.rateOn(sheetId, person.gradeOn(claim.getDate()), claim.getDate())
                .map(Rate::getPct)
                .map(pct -> sheet.rateStep(claim.getAmount(), pct, claim.getRoundingDate()))
                .orElse(null);
    }

    /**
     * The live due on a figure: the greater of the floor in force on the claim's date and the figure with the late
     * charge of an OPEN or PENDING claim overdue on the sheet's today added on top (no late charge on a claim deferred
     * on the sheet's today; a correction has neither floor nor late charge); null (n/a) stays null.
     */
    private BigDecimal liveDue(String sheetId, Person person, Claim claim, BigDecimal figure) {
        Sheet sheet = settings.get(sheetId);
        if (claim.isCorrection()) {
            return figure;
        }
        return sheet.floored(sheet.withLateCharge(figure, claim.getStatus().isOpen()
                && !claim.isDeferredOn(sheet.getToday()), overdueBy(sheet, person, claim), claim.getDate()), claim.getDate());
    }

    /** The day the claim goes overdue after: its due-by plus the grace days its person's grade has on its date. */
    private static LocalDate overdueBy(Sheet sheet, Person person, Claim claim) {
        return sheet.overdueBy(claim.getDueBy(), person.gradeOn(claim.getDate()), claim.getDate());
    }

    /** The amount as shown (two decimals) and the date as yyyy-mm-dd. */
    private static String record(String event, String user, Person person, Claim claim) {
        return event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString()
                + " date=" + claim.getDate();
    }

    /** The claim's number at the end of an audit record, or nothing when it has none. */
    private static String numberOf(String number) {
        return number == null ? "" : " number=" + number;
    }

    /**
     * The number a claim due by the date gets when opened: while the sheet numbers by period, the next in its
     * period's own sequence (X for no period); otherwise the next sheet-wide number.
     */
    public String nextNumber(String sheetId, LocalDate date) {
        if (settings.get(sheetId).numbersByPeriod()) {
            return sheets.nextSequenceNumber(sheetId, periods.nameOn(sheetId, date).orElse("X"));
        }
        return String.valueOf(sheets.nextClaimNumber(sheetId));
    }

    /** The terms of the person on the date: their own, else their grade's on that date, else the sheet's then. */

    private int termsFor(String sheetId, Person person, LocalDate date) {
        return person.getTerms() != null ? person.getTerms()
                : settings.termsFor(sheetId, person.gradeOn(date), date);
    }

    private LocalDate dateOr(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }

    /** The reference with surrounding spaces removed; null for a blank one (no reference). */
    /**
     * The tags of comma separated text, each trimmed and lower-cased, without repeats, sorted and joined by commas
     * (null when there are none), each noted as used on the sheet.
     */
    private String tagged(String sheetId, String text) {
        if (text == null) {
            return null;
        }
        List<String> list = SheetTag.parse(text);
        for (String tag : list) {
            if (!tags.existsBySheetIdAndTag(sheetId, tag)) {
                tags.save(new SheetTag(sheetId, tag));
            }
        }
        return list.isEmpty() ? null : String.join(",", list);
    }

    private static String refOf(String ref) {
        return ref == null || ref.isBlank() ? null : ref.trim();
    }
}
