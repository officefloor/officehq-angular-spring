package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.holiday.BusinessDays;
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.holiday.HolidaysChanged;
import net.officefloor.hq.app.person.Grade;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonWatch;
import net.officefloor.hq.app.person.PersonWatchRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.setting.DatedSettings;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.TodayChanged;
import net.officefloor.hq.app.voidreason.VoidReasonService;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final PersonWatchRepository watches;
    private final RateService rates;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final PeriodRepository periods;
    private final SettingService settings;
    private final HolidayService holidays;
    private final Rollup rollup;
    private final Audit audit;
    private final SheetTagRepository sheetTags;
    private final VoidReasonService voidReasons;

    public ClaimService(ClaimRepository claims, PersonRepository people, PersonWatchRepository watches,
            RateService rates, SheetService sheets, SheetRepository sheetRows, PeriodRepository periods,
            SettingService settings, HolidayService holidays, Rollup rollup, Audit audit,
            SheetTagRepository sheetTags, VoidReasonService voidReasons) {
        this.claims = claims;
        this.voidReasons = voidReasons;
        this.sheetTags = sheetTags;
        this.people = people;
        this.watches = watches;
        this.rates = rates;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.periods = periods;
        this.settings = settings;
        this.holidays = holidays;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's claims, newest first by their place in the list. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDesc(sheetId);
    }

    /** The terms a claim of the person created now is due by, whatever its date: those in force on the
     *  sheet's today, the person's own, else those of their grade, else the sheet's, each looked up on it. */
    private int termsOf(String sheetId, Person person) {
        LocalDate today = sheets.today(sheetId);
        Integer own = person.termsOn(today);
        return own != null ? own : settings.terms(sheetId, person.gradeOn(today), today);
    }

    /** Add a claim to a person; a claim with no date is dated the sheet's entry date while it is set,
     *  else the sheet's today. A claim belongs to the period its due-by falls in: one whose due-by falls
     *  inside a closed period is refused, and inside a soft-closed period is a late entry, audited after
     *  its adding. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tags) {
        Person person = person(sheetId, personId);
        LocalDate dated = date != null ? date : entryDateOr(sheetId);
        // The terms are fixed now, those in force on the sheet's today; the due-by counts them in business days.
        int terms = termsOf(sheetId, person);
        LocalDate dueBy = holidays.dueBy(sheetId, dated, terms);
        refuseIfClosed(sheetId, dueBy);
        refuseIfRetired(person, dated);
        refuseIfNotCorrecting(sheetId, personId, amount, corrects, null);
        String reference = refOf(ref);
        refuseIfRefUsed(sheetId, reference, null);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        Claim claim = new Claim(sheetId, personId, amount, dated, noteOf(note), corrects, terms, dueBy);
        claim.setRef(reference);
        List<String> tagged = tagsOf(tags);
        claim.setTags(tagged);
        // The sheet is locked (refuseIfClosed), so no other claim takes the same place.
        claim.setListed(claims.lastListed(sheetId) + 1);
        boolean late = periods.isSoftClosedOn(sheetId, dueBy);
        if (late) claim.markLateEntry();
        claim = claims.saveAndFlush(claim);
        useTags(sheetId, tagged);
        logStatus(sheetId, user, person, claim);
        recordChange("CLAIM_ADDED", user, person, claim, claim.getNumber(),
                (reference == null ? "" : " ref=" + reference)
                        + (tagged.isEmpty() ? "" : " tags=" + String.join(",", tagged)));
        if (late) recordChange("LATE_ENTRY", user, person, claim);
        rollup.recordChanges(user, before);
        return claim;
    }

    /** Open a claim's editor for the user: the claim is locked for other users until the editor is saved
     *  or cancelled, or the sheet's today changes. Refused, taking no lock, for a claim that cannot be
     *  edited (dated inside a closed period, or not a DRAFT) and a claim locked by another user. */
    @Transactional
    public Claim lock(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDueBy());
        refuseUnlessDraft(claim);
        refuseIfLocked(claim, user);
        claim.lockFor(user);
        return claims.saveAndFlush(claim);
    }

    /** Close the user's editor of a claim without saving it, releasing the user's lock on it; a lock
     *  of another user, or none, is left as it is. */
    @Transactional
    public Claim unlock(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (user.equals(claim.getLockedBy())) claim.lockFor(null);
        return claims.saveAndFlush(claim);
    }

    /** Once a holiday is added to the sheet, the due-by of every claim neither SETTLED nor VOID whose
     *  due-by is on or after the holiday is worked out again: the terms-th business day after the claim's
     *  date, with the terms the claim was fixed with, past the holidays now. */
    @EventListener
    @Transactional
    public void moveDueBys(HolidaysChanged change) {
        String sheetId = change.sheetId();
        Set<LocalDate> days = holidays.dates(sheetId);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) continue;
            if (claim.getDueBy().isBefore(change.date())) continue;
            if (claim.moveDueBy(BusinessDays.after(claim.getDate(), claim.getTerms(), days))) {
                claims.save(claim);
            }
        }
        claims.flush();
        rollup.recordChanges(change.user(), before);
    }

    /** Once the sheet's today is changed, every lock on its claims is released. */
    @EventListener
    @Transactional
    public void releaseLocks(TodayChanged change) {
        claims.releaseLocks(change.sheetId());
    }

    private static void refuseUnlessDraft(Claim claim) {
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited; only a DRAFT can.");
        }
    }

    /** Refuse a change to a claim whose editor another user has open. */
    private static void refuseIfLocked(Claim claim, String user) {
        if (claim.isLockedAgainst(user)) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "The claim is being edited by " + claim.getLockedBy() + ".");
        }
    }

    /** Change a draft claim's amount, date and note; a blank date takes the sheet's today. A claim that
     *  is no longer a draft is refused: to change an open claim, void it and add a new one. A claim
     *  another user's editor locks is refused; saving releases the user's own lock. A save whose date's
     *  due-by, as a claim of its person added on it would be fixed now, falls inside a closed period is
     *  refused, the date unchanged or not; the claim's own due-by stays as it was fixed. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tags) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        LocalDate dated = dateOr(sheetId, date);
        refuseIfClosed(sheetId, claim.getDueBy());
        refuseUnlessDraft(claim);
        refuseIfLocked(claim, user);
        Person person = person(sheetId, claim.getPersonId());
        refuseIfRetired(person, dated);
        refuseIfClosed(sheetId, holidays.dueBy(sheetId, dated, termsOf(sheetId, person)));
        if (!claim.getLines().isEmpty() && amount.compareTo(claim.getAmount()) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A claim with lines has the total of its lines as its amount.");
        }
        refuseIfNotCorrecting(sheetId, claim.getPersonId(), amount, corrects, claim.getId());
        String reference = refOf(ref);
        refuseIfRefUsed(sheetId, reference, claim.getId());
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.set(amount, dated, noteOf(note), corrects);
        claim.setRef(reference);
        List<String> tagged = tagsOf(tags);
        claim.setTags(tagged);
        useTags(sheetId, tagged);
        claim.lockFor(null);
        claim.setEditedBy(user);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_EDITED", user, person(sheetId, saved.getPersonId()), saved);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Move a claim to a new status; a change its status does not allow is refused, as is settling
     *  by the user who opened or last edited the claim, and an action the sheet's permission matrix does
     *  not allow the user's role. A clerk's settling of a claim whose due is above the sheet's
     *  clerk limit leaves it PENDING for a steward to approve (a due of n/a is within any limit; a
     *  steward settles anything). */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next) {
        return changeStatus(sheetId, user, id, next, null);
    }

    /** Make the claims a bulk settle settled (or left pending) the sheet's next settle batch: each shows
     *  its number. Its audit line, after the claims' own, carries the batch's number, how many claims it
     *  has and the frozen dues of those SETTLED added up. Returns the batch's number. */
    @Transactional
    public int markSettleBatch(String sheetId, String user, List<Claim> settled) {
        int batch = sheets.nextSettleBatch(sheetId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (Claim claim : settled) {
            Claim row = claims.findForUpdate(claim.getId(), sheetId).orElseThrow();
            row.setSettleBatch(batch);
            claims.save(row);
            claim.setSettleBatch(batch);
            if (claim.getStatus() == ClaimStatus.SETTLED && claim.getSettledDue() != null) {
                total = total.add(claim.getSettledDue());
            }
        }
        claims.flush();
        audit.recordOnCommit("BATCH_SETTLED sheet=" + sheetId + " by=" + user + " batch=" + batch
                + " count=" + settled.size() + " total=" + total.toPlainString());
        return batch;
    }

    /** Void a claim, as {@link #changeStatus}; while the sheet has void reasons, a void without one of
     *  them is refused. The reason is kept on the claim and ends its audit line. */
    @Transactional
    public Claim voidClaim(String sheetId, String user, long id, String reason) {
        return changeStatus(sheetId, user, id, ClaimStatus.VOID, reason);
    }

    private Claim changeStatus(String sheetId, String user, long id, ClaimStatus next, String reason) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDueBy());
        boolean voidingSettled = next == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED;
        if (voidingSettled) {
            refuseUnlessSettledVoidable(sheetId, user, claim);
        } else if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        } else {
            settings.refuseUnlessPermitted(sheetId, user, ACTIONS.get(next),
                    "Your role may not " + ACTIONS.get(next) + " a claim on this sheet.");
        }
        if (next == ClaimStatus.SETTLED && claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is deferred until " + claim.getDeferredUntil() + " and cannot be settled before then.");
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot settle it.");
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getEditedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who last edited a claim cannot settle it.");
        }
        String voidReason = next == ClaimStatus.VOID ? voidReasons.reasonToVoid(sheetId, reason) : null;
        Person person = person(sheetId, claim.getPersonId());
        if (next == ClaimStatus.OPEN && person.isFlaggedOn(sheets.today(sheetId))
                && settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The person is flagged; only a steward may open their claim.");
        }
        // Worked out while the claim is still open: settling freezes the due, its late charge included.
        BigDecimal dueNow = next == ClaimStatus.SETTLED ? due(sheetId, person, claim) : null;
        if (next == ClaimStatus.SETTLED) refuseIfUnproven(sheetId, person, claim, dueNow);
        BigDecimal dueOpening = next == ClaimStatus.OPEN ? due(sheetId, person, claim) : null;
        if (next == ClaimStatus.OPEN) refuseIfUnnoted(sheetId, claim, dueOpening);
        if (next == ClaimStatus.OPEN) refuseIfCapped(sheetId, claim);
        // Opened with a due above the review threshold, it waits for a steward's review, with no number yet.
        if (next == ClaimStatus.OPEN && isAboveReviewThreshold(sheetId, claim, dueOpening)) next = ClaimStatus.REVIEW;
        // Settled one at a time until a bulk settle marks it with its batch.
        if (next == ClaimStatus.SETTLED) claim.setSettleBatch(null);
        if (next == ClaimStatus.SETTLED && isAboveClerkLimit(sheetId, user, dueNow)) next = ClaimStatus.PENDING;
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        String number = next == ClaimStatus.OPEN ? nextNumber(sheetId, claim) : claim.getNumber();
        claim.setStatus(next);
        if (next == ClaimStatus.OPEN || next == ClaimStatus.REVIEW) claim.setOpenedBy(user);
        // A voided claim keeps its number, which is never given to another claim.
        claim.setNumber(number);
        if (next == ClaimStatus.SETTLED) claim.setSettledDue(dueNow);
        if (next == ClaimStatus.VOID) claim.setVoidReason(voidReason);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        String event = EVENTS.get(next);
        if (next == ClaimStatus.SETTLED) {
            recordChange(event, user, person, saved, number, " due=" + text(saved.getSettledDue()));
            notifyWatchers(user, person, saved);
        } else if (voidingSettled) {
            recordChange(event, user, person, saved, number, " reversed=" + text(saved.getSettledDue())
                    + reasonField(voidReason));
        } else {
            recordChange(event, user, person, saved, number, reasonField(voidReason));
        }
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Delete a DRAFT claim: it is DELETED, counting in no total and no status count, and can no longer be
     *  edited, opened or voided, only restored. Refused for a claim in any other status, a claim whose due-by
     *  is inside a closed period, and a claim another user's editor locks. */
    @Transactional
    public Claim delete(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (!claim.getStatus().canBecome(ClaimStatus.DELETED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be deleted; only a DRAFT can.");
        }
        refuseIfLocked(claim, user);
        return moveTo(sheetId, user, claim, ClaimStatus.DELETED, "CLAIM_DELETED");
    }

    /** Restore a DELETED claim, making it a DRAFT again; refused for a claim that is not DELETED, on a
     *  sheet's today other than the one it was deleted on, and for a claim whose due-by is inside a closed
     *  period. */
    @Transactional
    public Claim restore(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (!claim.getStatus().canBecome(ClaimStatus.DRAFT)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be restored; only a DELETED one can.");
        }
        if (!sheets.today(sheetId).equals(claim.deletedOn())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim can only be restored on the day it was deleted.");
        }
        return moveTo(sheetId, user, claim, ClaimStatus.DRAFT, "CLAIM_RESTORED");
    }

    /** Give the claim the status, logging it and writing the event. */
    private Claim moveTo(String sheetId, String user, Claim claim, ClaimStatus next, String event) {
        Person person = person(sheetId, claim.getPersonId());
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(next);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        recordChange(event, user, person, saved);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Defer an OPEN claim until a date after the sheet's today: until the sheet's today reaches it the
     *  claim counts in no total, is not overdue, cannot be settled and no statement binds it. Refused for
     *  a claim that is not OPEN, a date not after today, and a claim dated inside a closed period. */
    @Transactional
    public Claim defer(String sheetId, String user, long id, LocalDate until) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be deferred; only an OPEN one can.");
        }
        if (!until.isAfter(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A claim can only be deferred to a date after the sheet's today.");
        }
        Person person = person(sheetId, claim.getPersonId());
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.deferUntil(until);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_DEFERRED", user, person, saved, saved.getNumber(), " until=" + until);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Split an amount (more than 0.00 and less than the claim's amount) off an OPEN claim into a new
     *  OPEN claim of the same person, date and note, given the next number, opened by the user and due
     *  by the terms in force on the date; the claim keeps the rest and loses any figure a statement bound
     *  it to; it is a late entry when its due-by falls inside a soft-closed period. Refused for a claim that
     *  is not OPEN (a PENDING one included), a deferred claim, and a claim whose due-by, or the new claim's,
     *  is inside a closed period. */
    @Transactional
    public Split split(String sheetId, String user, long id, BigDecimal part) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be split; only an OPEN one can.");
        }
        if (claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is deferred until " + claim.getDeferredUntil() + " and cannot be split.");
        }
        if (part.signum() <= 0 || part.compareTo(claim.getAmount()) >= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The amount split off must be more than 0.00 and less than the claim's amount.");
        }
        Person person = person(sheetId, claim.getPersonId());
        // The terms are fixed now, those in force on the sheet's today; the due-by counts them in business days.
        int terms = termsOf(sheetId, person);
        LocalDate dueBy = holidays.dueBy(sheetId, claim.getDate(), terms);
        refuseIfClosed(sheetId, dueBy);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        Claim split = claim.splitOff(part, null, user, terms, dueBy, sheets.today(sheetId));
        String number = nextNumber(sheetId, split);
        split.setNumber(number);
        // Split off into a soft-closed period, the new claim is a late entry of it.
        if (periods.isSoftClosedOn(sheetId, dueBy)) split.markLateEntry();
        split.setListed(claims.lastListed(sheetId) + 1);
        Claim saved = claims.saveAndFlush(claim);
        split = claims.saveAndFlush(split);
        logStatus(sheetId, user, person, split);
        recordChange("CLAIM_SPLIT", user, person, saved, saved.getNumber(), " into=" + number);
        recordChange("CLAIM_OPENED", user, person, split);
        rollup.recordChanges(user, before);
        return new Split(saved, split);
    }

    /** What a split did: the claim split, keeping the rest of its amount, and the claim split off it. */
    public record Split(Claim original, Claim split) {
    }

    /** Re-date an OPEN or PENDING claim. Its due-by stays as it was fixed and a figure a statement bound
     *  it to stays bound, so it stays in its period and keeps its number. Refused unless the user is a
     *  steward of the sheet, and for a claim that is not OPEN or PENDING, a claim whose due-by is inside
     *  a closed period, and a new date after its person's retirement. */
    @Transactional
    public Claim redate(String sheetId, String user, long id, LocalDate date) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward re-dates a claim.");
        }
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-dated; only an OPEN or PENDING one can.");
        }
        Person person = person(sheetId, claim.getPersonId());
        refuseIfRetired(person, date);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        LocalDate old = claim.getDate();
        claim.redate(date);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_REDATED", user, person, saved, saved.getNumber(), " old=" + old + " new=" + date);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Waive an OPEN or PENDING claim's late charge, writing CLAIM_WAIVED: it bears no late charge from
     *  then on, whatever its overdue state. Refused unless the user is a steward of the sheet, for a claim
     *  in any other status, and for one already waived. */
    @Transactional
    public Claim waive(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward waives a claim's late charge.");
        }
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be waived; only an OPEN or PENDING one can.");
        }
        if (claim.isWaived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim's late charge is already waived.");
        }
        Person person = person(sheetId, claim.getPersonId());
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.waive();
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_WAIVED", user, person, saved);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Raise a claim's proofs count by one, and its count of the kind (none chosen: a receipt); a chosen
     *  kind is audited at the end of CLAIM_PROOF_ADDED. Refused for a SETTLED or VOID claim. No figure moves, so a
     *  claim dated inside a closed period may still have a proof added. */
    @Transactional
    public Claim addProof(String sheetId, String user, long id, ProofKind kind) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot have a proof added.");
        }
        Person person = person(sheetId, claim.getPersonId());
        claim.addProof(kind == null ? ProofKind.RECEIPT : kind);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_PROOF_ADDED", user, person, saved, saved.getNumber(), " proofs=" + saved.getProofs()
                + (kind == null ? "" : " kind=" + kind.text()));
        return saved;
    }

    /** Remind an OPEN or PENDING claim, writing REMIND and showing the sheet's today as its reminded date.
     *  A claim never escalated whose previous reminder was at least the sheet's escalation gap before the
     *  sheet's today is escalated too, writing ESCALATE after the REMIND.
     *  Refused for a claim in any other status, and for one already reminded on the sheet's today. No
     *  figure moves, so a claim dated inside a closed period may still be reminded. */
    @Transactional
    public Claim remind(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reminded; only an OPEN or PENDING one can.");
        }
        LocalDate today = sheets.today(sheetId);
        if (claim.getRemindedOn() != null && claim.getRemindedOn().equals(today)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim has already been reminded today.");
        }
        Person person = person(sheetId, claim.getPersonId());
        Integer gap = settings.escalateAfter(sheetId);
        LocalDate previous = claim.getRemindedOn();
        boolean escalates = gap != null && previous != null && !claim.isEscalated()
                && !previous.plusDays(gap).isAfter(today);
        claim.remind(today);
        if (escalates) claim.escalate();
        Claim saved = claims.saveAndFlush(claim);
        recordChange("REMIND", user, person, saved);
        if (escalates) recordChange("ESCALATE", user, person, saved);
        return saved;
    }

    /** Comment a claim, in any status, with the user and the sheet's today, writing COMMENT_ADDED.
     *  Refused for an empty comment. No figure moves, so a claim dated inside a closed period may
     *  still be commented. */
    @Transactional
    public Claim comment(String sheetId, String user, long id, String text) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A comment cannot be empty.");
        }
        Person person = person(sheetId, claim.getPersonId());
        claim.comment(user, sheets.today(sheetId), trimmed);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("COMMENT_ADDED", user, person, saved, saved.getNumber(), " text=" + trimmed);
        return saved;
    }

    /** Add a line to a DRAFT claim, its amount becoming the total of its lines, writing CLAIM_LINE_ADDED
     *  with the new amount. Adding a line edits the claim: refused, as an edit is, for a claim that is not
     *  a DRAFT, one whose due-by is inside a closed period and one another user's editor locks. */
    @Transactional
    public Claim addLine(String sheetId, String user, long id, String label, int qty, BigDecimal unit) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty() || qty < 1 || unit == null || unit.signum() < 0 || unit.stripTrailingZeros().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A line needs a label, a whole quantity of at least 1 and a unit amount with two decimals.");
        }
        refuseIfClosed(sheetId, claim.getDueBy());
        refuseUnlessDraft(claim);
        refuseIfLocked(claim, user);
        Person person = person(sheetId, claim.getPersonId());
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal scaled = unit.setScale(2);
        claim.addLine(trimmed, qty, scaled);
        claim.setEditedBy(user);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_LINE_ADDED", user, person, saved, saved.getNumber(),
                " label=" + trimmed + " qty=" + qty + " unit=" + scaled.toPlainString());
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Move a DRAFT, OPEN or PENDING claim to another person on the sheet, as their newest claim. It
     *  keeps its date, takes the next number in the sequence it is numbered in (a draft, having none,
     *  gets none; its old number is never used again) and loses any figure a statement bound it to (it is live again until
     *  its new person's next statement). Refused unless the sheet's permission matrix allows the user's
     *  role to reassign (by default, a steward), and for a
     *  settled or voided claim, a claim dated inside a closed period, a move to the claim's own person,
     *  to a person retired by the claim's date, or to a person by whose terms a claim added on its date now
     *  would be due inside a closed period (the claim's own due-by stays as it was fixed). */
    @Transactional
    public Claim reassign(String sheetId, String user, long id, long toPersonId) {
        return reassign(sheetId, user, id, toPersonId, false);
    }

    /** Move a claim as {@link #reassign(String, String, long, long)} does; a claim moved with others to
     *  its own person is not refused but re-listed as that person's newest claim, a numbered one taking the next number. */
    @Transactional
    public Claim reassign(String sheetId, String user, long id, long toPersonId, boolean withOthers) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDueBy());
        settings.refuseUnlessPermitted(sheetId, user, "reassign",
                "Your role may not move a claim to another person on this sheet.");
        if (claim.getStatus() != ClaimStatus.DRAFT && !claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be moved to another person.");
        }
        Person from = person(sheetId, claim.getPersonId());
        Person to = person(sheetId, toPersonId);
        if (!withOthers && from.getId().equals(to.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is already " + to.getName() + "'s.");
        }
        refuseIfRetired(to, claim.getDate());
        if (!from.getId().equals(to.getId())) {
            refuseIfClosed(sheetId, holidays.dueBy(sheetId, claim.getDate(), termsOf(sheetId, to)));
        }
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.moveTo(to.getId(), claims.lastListed(sheetId) + 1, sheets.today(sheetId));
        // Moved (to another person, or with others to its own), a numbered claim takes the next number;
        // its old one is not reused.
        if (claim.getNumber() != null && (withOthers || !from.getId().equals(to.getId()))) {
            claim.setNumber(nextNumber(sheetId, claim));
        }
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_REASSIGNED", user, to, saved, saved.getNumber(),
                " from=" + from.getName() + " to=" + to.getName());
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Approve a PENDING claim: it is SETTLED, its due (its figure, bound or live, and any late charge) frozen
     *  now. Refused unless the user is a steward of the sheet other than the one who opened or last
     *  edited it. */
    @Transactional
    public Claim approve(String sheetId, String user, long id) {
        Claim claim = pending(sheetId, user, id);
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot approve it.");
        }
        if (user.equals(claim.getEditedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who last edited a claim cannot approve it.");
        }
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal dueNow = due(sheetId, person, claim);
        refuseIfUnproven(sheetId, person, claim, dueNow);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.SETTLED);
        claim.setSettledDue(dueNow);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_APPROVED", user, person, saved, saved.getNumber(), " due=" + text(saved.getSettledDue()));
        notifyWatchers(user, person, saved);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Review a REVIEW claim: it is OPEN, given the next number; who opened it stays as it was. Refused
     *  unless the user is a steward of the sheet, and for a claim that is not REVIEW or whose due-by is
     *  inside a closed period. */
    @Transactional
    public Claim review(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward reviews a claim.");
        }
        if (claim.getStatus() != ClaimStatus.REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim is not waiting for review.");
        }
        refuseIfCapped(sheetId, claim);
        Person person = person(sheetId, claim.getPersonId());
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        claim.setNumber(nextNumber(sheetId, claim));
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_REVIEWED", user, person, saved, null, "");
        recordChange("CLAIM_OPENED", user, person, saved);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Re-settle a SETTLED claim: its due is frozen again at what it would be today as an open claim
     *  bound by no statement (today's rates, grades, floor and late charge). Refused unless the user is
     *  a steward of the sheet, and for a claim that is not SETTLED or is dated inside a closed period. */
    @Transactional
    public Claim resettle(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward re-settles a claim.");
        }
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-settled; only a SETTLED one can.");
        }
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal old = claim.getSettledDue();
        BigDecimal dueNow = LateCharge.dueAsOpen(claim, dueNow(sheetId, person, claim),
                settings.floor(sheetId, claim.getDate()), sheets.today(sheetId), grace(sheetId, person, claim),
                settings.dated(sheetId)::latePctOn, rounding(sheetId, claim));
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.setSettledDue(dueNow);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_RESETTLED", user, person, saved, saved.getNumber(),
                " old=" + text(old) + " new=" + text(saved.getSettledDue()));
        notifyWatchers(user, person, saved);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Reject a PENDING claim for a reason: it is OPEN again, still bound by its person's last
     *  statement if that statement bound it. Refused unless the user is a steward of the sheet. */
    @Transactional
    public Claim reject(String sheetId, String user, long id, String reason) {
        Claim claim = pending(sheetId, user, id);
        Person person = person(sheetId, claim.getPersonId());
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        logStatus(sheetId, user, person, claim);
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_REJECTED", user, person, saved, saved.getNumber(), " reason=" + reason);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** The PENDING claim a steward approves or rejects, locked; refused when it is not PENDING, is
     *  dated inside a closed period, or the user is not a steward of the sheet. */
    private Claim pending(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim is not waiting for approval.");
        }
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward approves or rejects a claim.");
        }
        return claim;
    }

    /** How many days after its settlement a steward may still void a SETTLED claim. */
    private static final int VOID_SETTLED_DAYS = 7;

    /** Refuse voiding a SETTLED claim unless the user is a steward of the sheet and the sheet's today is
     *  within {@value #VOID_SETTLED_DAYS} days of the claim's settlement, as its status log records it. */
    private void refuseUnlessSettledVoidable(String sheetId, String user, Claim claim) {
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward voids a settled claim.");
        }
        LocalDate settledOn = claim.settledOn();
        LocalDate today = sheets.today(sheetId);
        if (settledOn == null || today == null || today.isAfter(settledOn.plusDays(VOID_SETTLED_DAYS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A settled claim can only be voided within " + VOID_SETTLED_DAYS + " days of its settlement.");
        }
    }

    /** The permission matrix's action for each status a claim is moved to by opening, settling or voiding it. */
    private static final Map<ClaimStatus, String> ACTIONS = Map.of(
            ClaimStatus.OPEN, "open", ClaimStatus.SETTLED, "settle", ClaimStatus.PENDING, "settle",
            ClaimStatus.VOID, "void", ClaimStatus.REVIEW, "open");

    private static final Map<ClaimStatus, String> EVENTS = Map.of(
            ClaimStatus.OPEN, "CLAIM_OPENED", ClaimStatus.PENDING, "CLAIM_PENDING",
            ClaimStatus.SETTLED, "CLAIM_SETTLED", ClaimStatus.VOID, "CLAIM_VOIDED", ClaimStatus.REVIEW, "CLAIM_REVIEW");

    private static String text(BigDecimal due) {
        return due == null ? "n/a" : due.toPlainString();
    }

    /** The number a claim takes as it is opened: while the sheet's period numbering is in force (its
     *  today on or after the setting's date), the next of the claim's period (the one its due-by falls in), <period name>-<n>, or of
     *  claims in no period, X-<n> (each per sheet); otherwise FY<year>-<n>, the next number within the
     *  fiscal year its due-by falls in (named by the calendar year it starts in), of the sequence of that
     *  year the sheets of its tree share. */
    public String nextNumber(String sheetId, Claim claim) {
        if (!isPeriodNumbering(sheetId)) {
            int year = fiscalYearOf(sheetId, claim.getDueBy() != null ? claim.getDueBy() : claim.getDate());
            return "FY" + year + "-" + sheets.nextFiscalYearNumber(sheetId, year);
        }
        Period period = periodOf(sheetId, claim.getDueBy());
        if (period == null) return "X-" + sheets.nextSequenceNumber(sheetId, "none");
        return period.getName() + "-" + sheets.nextSequenceNumber(sheetId, "period:" + period.getId());
    }

    /** The fiscal year of the sheet the date falls in: the calendar year it starts in. */
    private int fiscalYearOf(String sheetId, LocalDate date) {
        int startMonth = settings.fiscalStartMonth(sheetId);
        return date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
    }

    /** Whether the sheet's period numbering is in force: its today on or after the setting's date. */
    private boolean isPeriodNumbering(String sheetId) {
        LocalDate from = settings.periodNumberingFrom(sheetId);
        return from != null && !sheets.today(sheetId).isBefore(from);
    }

    /** The sheet's period whose dates contain the date; null when none does. */
    private Period periodOf(String sheetId, LocalDate date) {
        return periods.findBySheetIdOrderByFromAsc(sheetId).stream()
                .filter((p) -> p.contains(date)).findFirst().orElse(null);
    }

    /** Refuse settling or approving a claim whose due is above the proof threshold in force on the claim's
     *  date (its person's grade's then when one is set, else the sheet's) while it has no proofs, or, while
     *  the sheet's proof kinds are set, no proof of one of them (a due of n/a, or no threshold, needs none). */
    private void refuseIfUnproven(String sheetId, Person person, Claim claim, BigDecimal due) {
        if (due == null) return;
        List<ProofKind> kinds = settings.proofKinds(sheetId);
        boolean proven = claim.getProofs() > 0 && kinds.stream().allMatch((k) -> claim.getProofs(k) > 0);
        if (proven) return;
        BigDecimal threshold = settings.proofThreshold(sheetId, person.gradeOn(claim.getDate()), claim.getDate());
        if (threshold != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim's due is above the proof threshold of " + threshold.toPlainString()
                            + (kinds.isEmpty() ? "; add a proof before it is settled."
                                    : "; add a proof of each kind of " + ProofKind.text(kinds) + " before it is settled."));
        }
    }

    /** Whether a claim being opened with the due given goes to REVIEW: its due is above the review
     *  threshold in force on its date (a due of n/a, or no threshold, does not). */
    private boolean isAboveReviewThreshold(String sheetId, Claim claim, BigDecimal due) {
        if (due == null) return false;
        BigDecimal threshold = settings.reviewThreshold(sheetId, claim.getDate());
        return threshold != null && due.compareTo(threshold) > 0;
    }

    /** Refuse opening a claim with an empty note whose due is above the sheet's note threshold in force
     *  on the claim's date (a due of n/a, or no threshold, needs none). */
    private void refuseIfUnnoted(String sheetId, Claim claim, BigDecimal due) {
        if (due == null || (claim.getNote() != null && !claim.getNote().isBlank())) return;
        BigDecimal threshold = settings.noteThreshold(sheetId, claim.getDate());
        if (threshold != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim's due is above the note threshold of " + threshold.toPlainString()
                            + "; add a note before it is opened.");
        }
    }

    /** Refuse opening a claim whose period (the one its due-by falls in) caps its person's claims, when
     *  the person already has that many OPEN or PENDING claims in it. The sheet is locked, so two opens
     *  cannot both pass the cap. */
    private void refuseIfCapped(String sheetId, Claim claim) {
        if (claim.getDueBy() == null) return;
        Period period = periodOf(sheetId, claim.getDueBy());
        if (period == null || period.getCap() == null) return;
        long held = claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .filter((c) -> !c.getId().equals(claim.getId()) && c.getPersonId().equals(claim.getPersonId())
                        && c.getStatus().isOpen() && c.getDueBy() != null && period.contains(c.getDueBy()))
                .count();
        if (held >= period.getCap()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The person already has " + period.getCap()
                    + " open claims in period " + period.getName() + ".");
        }
    }

    /** Whether the user is a clerk settling a claim whose due is above the sheet's clerk limit. */
    private boolean isAboveClerkLimit(String sheetId, String user, BigDecimal due) {
        if (due == null || settings.roleOf(sheetId, user) == Role.STEWARD) return false;
        BigDecimal limit = settings.clerkLimit(sheetId);
        return limit != null && due.compareTo(limit) > 0;
    }

    /** Refuse a change to a claim whose due-by (the date given) is inside a closed period. The sheet is locked first, as closing
     *  a period locks it, so a claim cannot change in a period while it is being closed. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        sheetRows.findForUpdate(sheetId);
        if (periods.isClosedOn(sheetId, date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim's due-by is inside a closed period.");
        }
    }

    /** Refuse a reference another claim on the sheet (other than the one given) already carries, compared
     *  without regard to case; no reference is never refused. */
    private void refuseIfRefUsed(String sheetId, String ref, Long id) {
        if (ref != null && claims.refUsed(sheetId, ref, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The reference '" + ref + "' is already used on this sheet.");
        }
    }

    /** Refuse a correction (a negative amount) unless it names, by number, a settled claim of the same person,
     *  and does not exceed what remains of that claim: its amount less the corrections already made against it
     *  (other than the claim given, null: none), VOID and DELETED ones left out. */
    private void refuseIfNotCorrecting(String sheetId, long personId, BigDecimal amount, String corrects, Long id) {
        if (amount.signum() >= 0) return;
        Claim target = corrects == null ? null : claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .filter(c -> c.getStatus() == ClaimStatus.SETTLED).orElse(null);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A negative amount must name, by number, a settled claim of the same person that it corrects.");
        }
        BigDecimal remains = target.getAmount().add(claims.correctedTotal(sheetId, personId, corrects, id));
        if (amount.negate().compareTo(remains) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A correction may not exceed what remains of the claim it corrects (" + remains.setScale(2) + ").");
        }
    }

    /** Refuse a claim dated after its person's retirement date. */
    private static void refuseIfRetired(Person person, LocalDate date) {
        if (person.isRetiredOn(date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is dated after " + person.getName() + " retired.");
        }
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
    }

    /** What is due on the claim now: the due fixed when it was settled, else the greater of the floor in
     *  force on its date and its figure with the late charge on top while it is open and overdue (the charge
     *  while no statement binds it; a bound claim's due is the figure the statement bound, as it was shown; a
     *  correction has no floor); null when the figure is n/a. */
    public BigDecimal due(String sheetId, Person person, Claim claim) {
        return LateCharge.dueOf(claim, figure(sheetId, person, claim), settings.floor(sheetId, claim.getDate()),
                sheets.today(sheetId), grace(sheetId, person, claim),
                settings.dated(sheetId)::latePctOn, rounding(sheetId, claim));
    }

    /** The grace days of the person's grade in force on the claim's date, in force on that date; 0 when none. */
    private int grace(String sheetId, Person person, Claim claim) {
        return settings.dated(sheetId).graceOn(person.gradeOn(claim.getDate()), claim.getDate());
    }

    /** The figure under the claim's due: the due fixed when it was settled, else the due as shown that
     *  its person's last statement bound it to (a PENDING claim included), else its live rate figure, before any late charge and floor;
     *  null when that is n/a. */
    public BigDecimal figure(String sheetId, Person person, Claim claim) {
        if (claim.getSettledDue() != null) return claim.getSettledDue();
        if (claim.isBound()) return claim.getStatedDue();
        return dueNow(sheetId, person, claim);
    }

    /** The claim's rate figure now: at the rate in force on the claim's date of its person's grade in
     *  force on that date, of the versions of the rates that apply to the claim, rounded as the claim's {@link Rounding} decides, before any late charge and
     *  floor; null when no rate of the grade applies by then. */
    private BigDecimal dueNow(String sheetId, Person person, Claim claim) {
        Rate rate = rates.inForceFor(sheetId, person.gradeOn(claim.getDate()), claim.getDate(), claim.getId());
        if (rate == null) return null;
        return claim.getAmount().multiply(rate.getPct()).divide(BigDecimal.valueOf(100), 2,
                rounding(sheetId, claim));
    }

    /** How many claims a candidate rate would change and by how much in all. */
    public record RatePreview(int count, BigDecimal diff) {
    }

    /** What a candidate percent for a grade, applied on every date, would do to the OPEN and PENDING claims of
     *  people on that grade on the claim's date: how many would show a different due, and the sum of new due
     *  less current due (an n/a due counting as 0.00). A claim bound by a statement does not change; nothing is saved. */
    @Transactional(readOnly = true)
    public RatePreview previewRate(String sheetId, Grade grade, BigDecimal pct) {
        Map<Long, Person> byId = new HashMap<>();
        for (Person p : people.findInListOrder(sheetId)) byId.put(p.getId(), p);
        int count = 0;
        BigDecimal diff = BigDecimal.ZERO.setScale(2);
        for (Claim claim : claims.findBySheetIdOrderByIdDesc(sheetId)) {
            if (!claim.getStatus().isOpen() || claim.isBound() || claim.getSettledDue() != null) continue;
            Person person = byId.get(claim.getPersonId());
            if (person == null || person.gradeOn(claim.getDate()) != grade) continue;
            RoundingMode rounding = rounding(sheetId, claim);
            BigDecimal figure = claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2, rounding);
            BigDecimal next = LateCharge.dueOf(claim, figure, settings.floor(sheetId, claim.getDate()),
                    sheets.today(sheetId), grace(sheetId, person, claim), settings.dated(sheetId)::latePctOn, rounding);
            BigDecimal current = due(sheetId, person, claim);
            if (current != null && current.compareTo(next) == 0) continue;
            count++;
            diff = diff.add(next.subtract(current == null ? BigDecimal.ZERO : current));
        }
        return new RatePreview(count, diff.setScale(2));
    }

    /** How the claim's live figures round under the sheet's policy date: by the date of the claim a
     *  correction corrects, else by its own. */
    private RoundingMode rounding(String sheetId, Claim claim) {
        return rounding(sheetId, claim, sheets.today(sheetId));
    }

    /** How the claim's figures round under the sheet's policy date with the date given standing in for
     *  the sheet's today. */
    public RoundingMode rounding(String sheetId, Claim claim, LocalDate today) {
        return rounding(sheetId, claim, today, settings.dated(sheetId));
    }

    /** How the claim's figures round by the rounding mode of the dated settings given in force on the date
     *  that decides it, else under the sheet's policy date with the date given standing in for its today. */
    public RoundingMode rounding(String sheetId, Claim claim, LocalDate today, DatedSettings dated) {
        Claim corrected = claim.isCorrection() && claim.getCorrects() != null
                ? claims.findBySheetIdAndPersonIdAndNumber(sheetId, claim.getPersonId(), claim.getCorrects()).orElse(null)
                : null;
        LocalDate roundsBy = Rounding.dateOf(claim, corrected);
        return Rounding.of(roundsBy, Rounding.policyDate(settings.policyDate(sheetId), today),
                dated.roundingOn(roundsBy));
    }

    /** The audit field of the reason a claim was voided with; blank when there is none. */
    private static String reasonField(String voidReason) {
        return voidReason == null ? "" : " reason=" + voidReason;
    }

    /** Log the status the claim has just taken, with its due as worked out now. */
    private void logStatus(String sheetId, String user, Person person, Claim claim) {
        claim.logStatus(user, sheets.today(sheetId), due(sheetId, person, claim));
    }

    private void recordChange(String event, String user, Person person, Claim claim) {
        recordChange(event, user, person, claim, claim.getNumber(), "");
    }

    /** The claim's audit line; a claim with a number carries it, then any extra fields. */
    private void recordChange(String event, String user, Person person, Claim claim, String number, String extra) {
        audit.recordOnCommit(event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number == null ? "" : " number=" + number) + extra);
    }

    /** Tell each user watching the person, in the order they started watching, that the claim was settled. */
    private void notifyWatchers(String user, Person person, Claim claim) {
        for (PersonWatch watch : watches.findByPersonIdOrderByIdAsc(person.getId())) {
            audit.recordOnCommit("NOTIFY sheet=" + claim.getSheetId() + " by=" + user + " user=" + watch.getUserName()
                    + " person=" + person.getName() + " number=" + claim.getNumber());
        }
    }

    private LocalDate dateOr(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }

    /** The date a new claim given none takes: the sheet's entry date while it is set, else its today. */
    public LocalDate entryDateOr(String sheetId) {
        LocalDate entry = settings.entryDate(sheetId);
        return entry != null ? entry : sheets.today(sheetId);
    }

    /** The claim's timeline: the audit lines about it, newest first, as the file holds them. */
    @Transactional(readOnly = true)
    public List<String> timeline(String sheetId, long id) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        Person person = person(sheetId, claim.getPersonId());
        return audit.claimLines(sheetId, claim.getNumber(), person.getName(),
                claim.getAmount().toPlainString(), claim.getDate().toString());
    }

    /** Every tag ever used on the sheet, by name. */
    @Transactional(readOnly = true)
    public List<String> tags(String sheetId) {
        return sheetTags.findBySheetIdOrderByNameAsc(sheetId).stream().map(SheetTag::getName).toList();
    }

    /** Keep the tags as used on the sheet. */
    public void useTags(String sheetId, List<String> tagged) {
        for (String tag : tagged) {
            if (!sheetTags.existsBySheetIdAndName(sheetId, tag)) sheetTags.save(new SheetTag(sheetId, tag));
        }
    }

    /** Tags as typed, comma separated: each trimmed and lower-cased, blanks and repeats dropped, sorted. */
    public static List<String> tagsOf(String tags) {
        if (tags == null) return List.of();
        return Arrays.stream(tags.split(",")).map(t -> t.trim().toLowerCase(Locale.ROOT)).filter(t -> !t.isEmpty())
                .distinct().sorted().toList();
    }

    /** A reference as given, trimmed; none when blank. */
    public static String refOf(String ref) {
        return ref == null || ref.isBlank() ? null : ref.trim();
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
