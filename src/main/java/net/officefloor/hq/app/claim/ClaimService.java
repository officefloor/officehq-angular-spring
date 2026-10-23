package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.Retirement;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.WatchService;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.TodayChanged;
import net.officefloor.hq.app.voidreason.VoidReasonService;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claims people on a sheet make: listed, added, edited and moved through their statuses. */
@Service
public class ClaimService {

    /** Shown in place of a due when the grade has no rate. */
    private static final String NO_RATE = "n/a";

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final Dues dues;
    private final SheetService sheets;
    private final Rollups rollups;
    private final PeriodService periods;
    private final RoleService roles;
    private final WatchService watches;
    private final HolidayService holidays;
    private final VoidReasonService voidReasons;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, Dues dues, SheetService sheets,
            Rollups rollups, PeriodService periods, RoleService roles, WatchService watches, HolidayService holidays,
            VoidReasonService voidReasons, Audit audit) {
        this.watches = watches;
        this.holidays = holidays;
        this.voidReasons = voidReasons;
        this.periods = periods;
        this.roles = roles;
        this.claims = claims;
        this.people = people;
        this.dues = dues;
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** A settle batch: its number, and the claims it settled, as they are now, in the order given. */
    public record Batch(int number, List<Claim> claims) {
    }

    /**
     * Make the claims a bulk settle settled the sheet's next settle batch: each carries its number.
     * Audited as BATCH_SETTLED with the batch number, how many claims it holds, and the frozen figures
     * of those of them SETTLED, added up.
     */
    @Transactional
    public Batch batch(String sheetId, String user, List<Long> claimIds) {
        int number = sheets.lock(sheetId).nextSettleBatch();
        List<Claim> batched = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Long id : claimIds) {
            Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow();
            claim.settledInBatch(number);
            if (claim.getStatus() == ClaimStatus.SETTLED && claim.getSettledDue() != null) {
                total = total.add(claim.getSettledDue());
            }
            batched.add(claims.save(claim));
        }
        audit.recordOnCommit("BATCH_SETTLED sheet=" + sheetId + " by=" + user + " batch=" + number
                + " count=" + batched.size() + " total=" + total.setScale(2, RoundingMode.HALF_UP).toPlainString());
        return new Batch(number, batched);
    }

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDescIdDesc(sheetId);
    }

    /**
     * Add a claim for a person on the sheet; with no date it is dated the sheet's entry date
     * while one is set, else the sheet's today. One given a date in a soft-closed period (with no date given, the
     * sheet's today in one), or due by in one, is a late entry, audited as LATE_ENTRY after its CLAIM_ADDED. A DRAFT may be added
     * due by inside a closed period (it cannot be opened or changed there). Refused when it is a correction that does not name a SETTLED claim
     * of the same person (see {@link #refuseUnlessCorrects}).
     */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        return add(sheetId, user, personId, amount, date, note, corrects, null);
    }

    /**
     * Add a claim (see {@link #add(String, String, long, BigDecimal, LocalDate, String, String)}) with an
     * external reference (blank: none); refused when the reference is already used on the sheet, comparing
     * without regard to case. CLAIM_ADDED ends with ref=&lt;reference&gt; when one was given.
     */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref) {
        return add(sheetId, user, personId, amount, date, note, corrects, ref, null);
    }

    /**
     * Add a claim (see {@link #add(String, String, long, BigDecimal, LocalDate, String, String, String)}) with
     * its tags, comma separated (see {@link Claim#tagsOf}). CLAIM_ADDED ends with tags=&lt;tags, sorted and
     * comma separated&gt; when any were given.
     */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tags) {
        Rollups.Rollup before = rollups.take(sheetId);
        Person person = person(sheetId, personId);
        LocalDate dated = date != null ? date : sheets.load(sheetId).defaultClaimDate();
        Sheet sheet = sheets.lock(sheetId);
        int terms = terms(sheet, person, sheet.getToday());
        LocalDate dueBy = holidays.dueBy(sheetId, dated, terms);
        periods.refuseIfClosed(sheetId, dueBy);
        refuseIfRetired(person, dated);
        refuseUnlessCorrects(sheetId, personId, amount, corrects, null);
        String referred = reference(ref);
        refuseIfReferred(sheetId, referred, null);
        Claim added = new Claim(sheetId, personId, money(amount), dated, terms, dueBy, clean(note), corrects);
        added.refer(referred);
        List<String> tagged = Claim.tagsOf(tags);
        added.tag(tagged);
        // A claim dated by the sheet's entry date is meant to be dated there: it is late by the sheet's today.
        LocalDate entered = date != null ? date : sheet.getToday();
        if (periods.softClosedOn(sheetId, entered) || periods.softClosedOn(sheetId, dueBy)) {
            added.markLateEntry();
        }
        Claim claim = claims.saveAndFlush(added);
        claim = logged(claim, user, person);
        audited("CLAIM_ADDED", sheetId, user, person, claim, claim.getNumber(),
                (referred != null ? " ref=" + referred : "")
                        + (tagged.isEmpty() ? "" : " tags=" + String.join(",", tagged)));
        if (claim.isLateEntry()) {
            audited("LATE_ENTRY", sheetId, user, person, claim);
        }
        rollups.audit(sheetId, user, before);
        return claim;
    }

    /**
     * Change a claim's amount, date, note and the number it corrects; with no date it keeps its own. Only a DRAFT claim can be
     * edited: an OPEN one is changed by voiding it and adding a new one. It keeps the due-by fixed when it
     * was added, and so its period. Refused when the claim's due-by, or the due-by a new date would give it
     * (the terms-th business day after it), is inside a closed period, or when it
     * would be a correction that does not name a SETTLED claim of the same person, or when its external
     * reference (null: kept as it is; blank: none) is already used by another claim on the sheet. Its tags
     * (null: kept as they are) are set from the comma separated text (see {@link Claim#tagsOf}).
     */
    @Transactional
    public Claim edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tags) {
        Rollups.Rollup before = rollups.take(sheetId);
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, claimId);
        refuseUnlessEditable(sheetId, user, claim);
        if (date != null) {
            refuseIfRetired(person(sheetId, claim.getPersonId()), date);
            if (!date.equals(claim.getDate())) {
                periods.refuseIfClosed(sheetId, holidays.dueBy(sheetId, date, claim.getTerms()));
            }
        }
        if (!claim.getLines().isEmpty() && money(amount).compareTo(claim.getAmount()) != 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim with lines takes its amount from them: " + claim.getAmount().toPlainString() + ".");
        }
        refuseUnlessCorrects(sheetId, claim.getPersonId(), amount, corrects, claim.getId());
        String referred = ref == null ? claim.getRef() : reference(ref);
        refuseIfReferred(sheetId, referred, claim.getId());
        claim.refer(referred);
        if (tags != null) {
            claim.tag(Claim.tagsOf(tags));
        }
        claim.change(money(amount), date != null ? date : claim.getDate(), clean(note), corrects);
        claim.lockFor(null);
        claim.editedBy(user);
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_EDITED", sheetId, user, person(sheetId, saved.getPersonId()), saved);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Add a line to a DRAFT claim: a label, a quantity (at least 1) and a unit amount (two decimals); the
     * claim's amount becomes its lines' quantities times their unit amounts, added up. It counts as an
     * edit of the claim, refused as one is (see {@link #refuseUnlessEditable}), and is audited as
     * CLAIM_LINE_ADDED with the claim's new amount, ending label=&lt;label&gt; qty=&lt;qty&gt; unit=&lt;unit&gt;.
     */
    @Transactional
    public Claim addLine(String sheetId, String user, long claimId, String label, int qty, BigDecimal unit) {
        String trimmed = clean(label);
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A line needs a label.");
        }
        Rollups.Rollup before = rollups.take(sheetId);
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, claimId);
        refuseUnlessEditable(sheetId, user, claim);
        BigDecimal unitAmount = money(unit);
        claim.addLine(trimmed, qty, unitAmount);
        claim.editedBy(user);
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_LINE_ADDED", sheetId, user, person(sheetId, saved.getPersonId()), saved, saved.getNumber(),
                " label=" + trimmed + " qty=" + qty + " unit=" + unitAmount.toPlainString());
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Open a claim's editor for the user: the claim is locked for every other user until the editor is
     * saved or cancelled, or the sheet's today changes. Refused, taking no lock, when the claim cannot
     * be edited (see {@link #edit}) or another user's editor holds it.
     */
    @Transactional
    public Claim lock(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, claimId);
        refuseUnlessEditable(sheetId, user, claim);
        claim.lockFor(user);
        return claims.saveAndFlush(claim);
    }

    /** Cancel the user's editor of a claim, releasing its lock; a lock another user holds is left as it is. */
    @Transactional
    public Claim unlock(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (user.equals(claim.getLockedBy())) {
            claim.lockFor(null);
            claim = claims.saveAndFlush(claim);
        }
        return claim;
    }

    /** The sheet's today was changed: every editor's lock on its claims is released. */
    @EventListener
    public void todayChanged(TodayChanged change) {
        claims.unlockAll(change.sheetId());
    }

    /**
     * Refuse an edit of a claim that is not a DRAFT, that is due by inside a closed period, or that
     * another user's editor holds (423).
     */
    private void refuseUnlessEditable(String sheetId, String user, Claim claim) {
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited; only a DRAFT can.");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.isLockedAgainst(user)) {
            throw new ResponseStatusException(HttpStatus.LOCKED,
                    "The claim is being edited by " + claim.getLockedBy() + ".");
        }
    }

    /**
     * Move a claim to a new status, recording the event; refused (and nothing written) when the
     * claim's current status does not allow the move. Settling fixes the claim's due at the rate
     * the person's grade has in force on the claim's date, or at the figure a statement bound it to,
     * with the late charge it has then frozen in.
     * Opening numbers the claim and records who opened it; a voided claim keeps its number, which
     * is never reused. The user who opened a claim,
     * or last edited it, is refused when settling it. A clerk settling a claim whose due is above the sheet's clerk limit
     * (a due of n/a is within any limit) sends it PENDING instead, for a steward to approve or reject;
     * a steward settles any. A move the sheet's permission matrix does not allow the user's role is
     * refused. A claim due by inside a closed period cannot be moved, nor can a claim
     * deferred on the sheet's today be settled, nor a claim opened by anyone but a steward whose person is
     * flagged on the sheet's today, nor a claim opened whose person already has as many OPEN
     * or PENDING claims due by in its period as the period's claim cap. A claim opened whose due is above the
     * sheet's review threshold in force on its date (a due of n/a is within any threshold) goes to REVIEW
     * instead, with no number yet, audited as CLAIM_REVIEW (see {@link #review}). A SETTLED claim is voided only by a steward, within
     * {@link ClaimStatus#VOID_SETTLED_DAYS} days of its settlement; its frozen figure leaves every
     * total and the audit records it as reversed.
     */
    @Transactional
    public Claim move(String sheetId, String user, long claimId, ClaimStatus next) {
        return move(sheetId, user, claimId, next, null);
    }

    /**
     * Move a claim as {@link #move(String, String, long, ClaimStatus)} does, voiding it with a reason:
     * while the sheet's list of void reasons is not empty, a void needs one from it (see
     * {@link VoidReasonService#require}); the claim keeps it and its audit record ends with it.
     */
    @Transactional
    public Claim move(String sheetId, String user, long claimId, ClaimStatus next, String reason) {
        // Every move takes the sheet's lock before reading the claim, so concurrent opens on the
        // sheet are serialised: each sees the other's committed claim and number.
        LocalDate today = sheets.lock(sheetId).getToday();
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        String action = switch (next) {
            case OPEN -> "open";
            case SETTLED -> "settle";
            case VOID -> "void";
            case PENDING, DRAFT, DELETED, REVIEW -> throw new IllegalArgumentException("No move to " + next + ".");
        };
        if (!roles.permits(sheetId, user, action)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not " + action + " a claim.");
        }
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        boolean voidingSettled = next == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED;
        if (voidingSettled) {
            refuseVoidingSettled(sheetId, user, claim, today);
        }
        String voidReason = next == ClaimStatus.VOID ? voidReasons.require(sheetId, reason) : null;
        if (next == ClaimStatus.SETTLED && claim.isDeferredOn(today)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is deferred until " + claim.getDeferredUntil() + ": it cannot be settled before then.");
        }
        if (next == ClaimStatus.SETTLED && claim.isOpenedOrEditedBy(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The user who opened or last edited a claim cannot settle it.");
        }
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal settledDue = next == ClaimStatus.SETTLED
                ? dues.settling(claim, person.gradeOn(claim.getDate()))
                : null;
        if (next == ClaimStatus.OPEN && person.flaggedOn(today)
                && !Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    person.getName() + " is flagged (" + person.getFlagReason() + "): only a steward opens their claims.");
        }
        if (next == ClaimStatus.OPEN) {
            periods.refuseIfCapped(sheetId, claim);
            BigDecimal due = dues.due(claim, person.gradeOn(claim.getDate()));
            refuseUnnoted(sheetId, claim, due);
            if (overReviewThreshold(sheetId, claim, due)) {
                next = ClaimStatus.REVIEW;
            }
        }
        if (next == ClaimStatus.SETTLED) {
            refuseUnproved(sheetId, claim, person.gradeOn(claim.getDate()), settledDue);
        }
        if (next == ClaimStatus.SETTLED && overClerkLimit(sheetId, user, person.gradeOn(today), settledDue)) {
            next = ClaimStatus.PENDING;
            settledDue = null;
        }
        claim.become(next);
        if (next == ClaimStatus.OPEN) {
            claim.number(periods.nextClaimNumber(sheetId, claim.getDueBy()));
        }
        if (next == ClaimStatus.OPEN || next == ClaimStatus.REVIEW) {
            claim.openedBy(user);
        }
        if (next == ClaimStatus.SETTLED) {
            claim.settleDue(settledDue);
        }
        if (next == ClaimStatus.VOID) {
            claim.voidedWith(voidReason);
        }
        Claim saved = logged(claims.saveAndFlush(claim), user, person);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case PENDING -> "CLAIM_PENDING";
            case REVIEW -> "CLAIM_REVIEW";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT, DELETED -> throw new IllegalStateException();
        };
        String suffix = next == ClaimStatus.SETTLED
                ? " due=" + (saved.getSettledDue() != null ? saved.getSettledDue().toPlainString() : NO_RATE)
                : voidingSettled
                ? " reversed=" + (saved.getSettledDue() != null ? saved.getSettledDue().toPlainString() : NO_RATE)
                : "";
        if (voidReason != null) {
            suffix += " reason=" + voidReason;
        }
        audited(event, sheetId, user, person, saved, saved.getNumber(), suffix);
        if (next == ClaimStatus.SETTLED) {
            watches.notifyWatchers(sheetId, user, person, saved.getNumber());
        }
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Delete a DRAFT claim: it is DELETED, counts nowhere and cannot be edited, opened or voided, until
     * it is restored. Refused when the claim is not a DRAFT or is due by inside a closed period.
     */
    @Transactional
    public Claim delete(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, claimId);
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        if (!claim.getStatus().canBecome(ClaimStatus.DELETED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be deleted; only a DRAFT can.");
        }
        claim.become(ClaimStatus.DELETED);
        claim.lockFor(null);
        Person person = person(sheetId, claim.getPersonId());
        Claim saved = logged(claims.saveAndFlush(claim), user, person);
        audited("CLAIM_DELETED", sheetId, user, person, saved);
        return saved;
    }

    /**
     * Restore a DELETED claim to a DRAFT; refused when the claim is not DELETED, when the sheet's today
     * is not the day it was deleted, or when it is due by inside a closed period.
     */
    @Transactional
    public Claim restore(String sheetId, String user, long claimId) {
        LocalDate today = sheets.lock(sheetId).getToday();
        Claim claim = claim(sheetId, claimId);
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        if (!claim.getStatus().canBecome(ClaimStatus.DRAFT)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be restored; only a DELETED one can.");
        }
        if (today == null || !today.equals(claim.deletedOn())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim can only be restored on the day it was deleted, " + claim.deletedOn() + ".");
        }
        if (claim.isCorrection()) {
            refuseUnlessCorrects(sheetId, claim.getPersonId(), claim.getAmount(), claim.getCorrects(), claim.getId());
        }
        claim.become(ClaimStatus.DRAFT);
        Person person = person(sheetId, claim.getPersonId());
        Claim saved = logged(claims.saveAndFlush(claim), user, person);
        audited("CLAIM_RESTORED", sheetId, user, person, saved);
        return saved;
    }

    /**
     * Add a proof of a kind (null when none was chosen: a receipt) to a claim; refused when the claim is
     * SETTLED or VOID. The audit record carries the kind only when one was chosen.
     */
    @Transactional
    public Claim addProof(String sheetId, String user, long claimId, String kind) {
        sheets.lock(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot take a proof.");
        }
        claim.addProof(kind == null ? "receipt" : kind);
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_PROOF_ADDED", sheetId, user, person(sheetId, saved.getPersonId()), saved, saved.getNumber(),
                " proofs=" + saved.getProofs() + (kind == null ? "" : " kind=" + kind));
        return saved;
    }

    /**
     * Comment on a claim, in any status: the comment is kept with the user and the sheet's today, and
     * audited as COMMENT_ADDED ending text=&lt;text&gt;. Refused when the text is blank.
     */
    @Transactional
    public Claim comment(String sheetId, String user, long claimId, String text) {
        String trimmed = clean(text);
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A comment cannot be empty.");
        }
        LocalDate today = sheets.lock(sheetId).getToday();
        Claim claim = claim(sheetId, claimId);
        claim.comment(user, today, trimmed);
        Claim saved = claims.saveAndFlush(claim);
        audited("COMMENT_ADDED", sheetId, user, person(sheetId, saved.getPersonId()), saved, saved.getNumber(),
                " text=" + trimmed);
        return saved;
    }

    /**
     * Remind an OPEN or PENDING claim: the claim shows the sheet's today as its reminded date. Refused
     * when the claim is in any other status, or was already reminded on the sheet's today.
     */
    @Transactional
    public Claim remind(String sheetId, String user, long claimId) {
        Sheet sheet = sheets.lock(sheetId);
        LocalDate today = sheet.getToday();
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reminded; only an OPEN or PENDING one can.");
        }
        if (today.equals(claim.getRemindedOn())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim was already reminded on " + today + ".");
        }
        LocalDate previous = claim.getRemindedOn();
        Integer gap = sheet.getEscalateAfter();
        boolean escalates = gap != null && previous != null && !claim.isEscalated()
                && !previous.plusDays(gap).isAfter(today);
        claim.remind(today);
        if (escalates) {
            claim.escalate();
        }
        Claim saved = claims.saveAndFlush(claim);
        Person person = person(sheetId, saved.getPersonId());
        audited("REMIND", sheetId, user, person, saved);
        if (escalates) {
            audited("ESCALATE", sheetId, user, person, saved);
        }
        return saved;
    }

    /**
     * Defer an OPEN claim until a date after the sheet's today: until the sheet's today reaches it,
     * the claim counts in no total, is not overdue, cannot be settled and is left unbound by a
     * statement. Refused when the claim is not OPEN, when the date is not after the sheet's today, or
     * when the claim is due by inside a closed period.
     */
    @Transactional
    public Claim defer(String sheetId, String user, long claimId, LocalDate until) {
        LocalDate today = sheets.lock(sheetId).getToday();
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be deferred; only an OPEN one can.");
        }
        if (!until.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim can only be deferred to a date after the sheet's today, " + today + ".");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        claim.defer(until);
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_DEFERRED", sheetId, user, person(sheetId, saved.getPersonId()), saved, saved.getNumber(),
                " until=" + until);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Waive an OPEN or PENDING claim's late charge: from then on it bears none, whatever its overdue
     * state. Only a steward waives; refused when the claim is not OPEN or PENDING, or is already waived.
     */
    @Transactional
    public Claim waive(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward waives a claim's late charge.");
        }
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim's late charge cannot be waived; only an OPEN or PENDING one's can.");
        }
        if (claim.isWaived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim's late charge is already waived.");
        }
        claim.waive();
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_WAIVED", sheetId, user, person(sheetId, saved.getPersonId()), saved);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Hold an OPEN or PENDING claim, or release it when held: held, it leaves the live totals as a held
     * person's claims do, unless bound by a statement. Refused when the claim is not OPEN or PENDING.
     */
    @Transactional
    public Claim toggleHold(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be held or released; only an OPEN or PENDING one can.");
        }
        claim.hold(!claim.isHeld());
        Claim saved = claims.saveAndFlush(claim);
        audited(saved.isHeld() ? "CLAIM_HELD" : "CLAIM_RELEASED", sheetId, user, person(sheetId, saved.getPersonId()), saved);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Approve a PENDING claim: it becomes SETTLED, its due fixed at this moment at the figure a statement bound it to, else its live figure, with
     * the late charge it has then. Only a steward approves, and not the user who opened or last edited the claim.
     */
    @Transactional
    public Claim approve(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = pending(sheetId, user, claimId);
        if (claim.isOpenedOrEditedBy(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The user who opened or last edited a claim cannot approve it.");
        }
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal approvedDue = dues.approving(claim, person.gradeOn(claim.getDate()));
        refuseUnproved(sheetId, claim, person.gradeOn(claim.getDate()), approvedDue);
        claim.settleDue(approvedDue);
        claim.become(ClaimStatus.SETTLED);
        Claim saved = logged(claims.saveAndFlush(claim), user, person);
        audited("CLAIM_APPROVED", sheetId, user, person, saved, saved.getNumber(),
                " due=" + (saved.getSettledDue() != null ? saved.getSettledDue().toPlainString() : NO_RATE));
        watches.notifyWatchers(sheetId, user, person, saved.getNumber());
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Review a REVIEW claim: it becomes OPEN, numbered now, keeping who opened it; audited as
     * CLAIM_REVIEWED, then CLAIM_OPENED with its number. Only a steward reviews; refused when the claim
     * is not REVIEW, or is due by inside a closed period.
     */
    @Transactional
    public Claim review(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward reviews a claim.");
        }
        if (claim.getStatus() != ClaimStatus.REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim is not awaiting review.");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        Person person = person(sheetId, claim.getPersonId());
        claim.become(ClaimStatus.OPEN);
        claim.number(periods.nextClaimNumber(sheetId, claim.getDueBy()));
        Claim saved = logged(claims.saveAndFlush(claim), user, person);
        audited("CLAIM_REVIEWED", sheetId, user, person, saved, null, "");
        audited("CLAIM_OPENED", sheetId, user, person, saved);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Re-settle a SETTLED claim: its due is fixed again at the figure it would have today as an OPEN
     * claim bound by no statement (today's rates, grades, floor and late charge). Only a steward
     * re-settles; refused when the claim is not SETTLED or is due by inside a closed period.
     */
    @Transactional
    public Claim resettle(String sheetId, String user, long claimId) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward re-settles a claim.");
        }
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-settled; only a SETTLED one can.");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal old = claim.getSettledDue();
        claim.settleDue(dues.resettling(claim, person.gradeOn(claim.getDate())));
        Claim saved = logged(claims.saveAndFlush(claim), user, person);
        audited("CLAIM_RESETTLED", sheetId, user, person, saved, saved.getNumber(),
                " old=" + (old != null ? old.toPlainString() : NO_RATE)
                        + " new=" + (saved.getSettledDue() != null ? saved.getSettledDue().toPlainString() : NO_RATE));
        watches.notifyWatchers(sheetId, user, person, saved.getNumber());
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Reject a PENDING claim with a reason: it is OPEN again, bound again by its person's last
     * statement when that statement included it. Only a steward rejects.
     */
    @Transactional
    public Claim reject(String sheetId, String user, long claimId, String reason) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = pending(sheetId, user, claimId);
        claim.become(ClaimStatus.OPEN);
        Claim saved = logged(claims.saveAndFlush(claim), user, person(sheetId, claim.getPersonId()));
        audited("CLAIM_REJECTED", sheetId, user, person(sheetId, saved.getPersonId()), saved, saved.getNumber(),
                " reason=" + reason.trim());
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Move a DRAFT, OPEN or PENDING claim to another person on the sheet: it keeps its date, takes the
     * next number in the sequence it is numbered in (its old one never used again; a DRAFT gets none), and loses any figure a statement bound it to, its due live again until its new person's
     * next statement. Only a role the sheet's permission matrix allows (a steward by
     * default) reassigns. Refused when the claim is SETTLED or VOID, when it is
     * due by inside a closed period, when the new person is its own or is retired before its date, or (for
     * a correction, which still corrects the claim it named) when its person has no SETTLED claim of that number.
     */
    @Transactional
    public Claim reassign(String sheetId, String user, long claimId, long personId) {
        return reassign(sheetId, user, claimId, personId, false);
    }

    /**
     * Reassign a claim as {@link #reassign(String, String, long, long)} does; when {@code toOwn} is set,
     * a claim already the new person's is not refused but reassigned to them again (as a bulk
     * reassign does to each selected claim).
     */
    @Transactional
    public Claim reassign(String sheetId, String user, long claimId, long personId, boolean toOwn) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (!roles.permits(sheetId, user, "reassign")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not reassign a claim.");
        }
        if (claim.getStatus() != ClaimStatus.DRAFT && !claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reassigned.");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        Person from = person(sheetId, claim.getPersonId());
        Person to = person(sheetId, personId);
        if (!toOwn && from.getId().equals(to.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is already " + to.getName() + "'s.");
        }
        refuseIfRetired(to, claim.getDate());
        refuseUnlessCorrects(sheetId, from.getId(), claim.getAmount(), claim.getCorrects(), claim.getId());
        claim.reassign(to.getId());
        claim.unbind();
        if (claim.getNumber() != null) {
            claim.number(periods.nextClaimNumber(sheetId, claim.getDueBy()));
        }
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_REASSIGNED", sheetId, user, to, saved, saved.getNumber(),
                " from=" + from.getName() + " to=" + to.getName());
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Re-date an OPEN or PENDING claim: it keeps the due-by fixed when it was added, and so its period
     * and number, and any figure a statement bound it to. Only a steward re-dates; refused when the claim
     * is not OPEN or PENDING, when its due-by is inside a closed period, or when the new date is after
     * its person's retirement.
     */
    @Transactional
    public Claim redate(String sheetId, String user, long claimId, LocalDate date) {
        sheets.lock(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward re-dates a claim.");
        }
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-dated; only an OPEN or PENDING one can.");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        Person person = person(sheetId, claim.getPersonId());
        refuseIfRetired(person, date);
        LocalDate old = claim.getDate();
        claim.redate(date);
        Claim saved = claims.saveAndFlush(claim);
        audited("CLAIM_REDATED", sheetId, user, person, saved, saved.getNumber(), " old=" + old + " new=" + date);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /** What a split did: the original claim, as it is now, and the new claim split off it. */
    public record Split(Claim original, Claim split) {
    }

    /**
     * Split an amount (above 0.00 and below the claim's amount) off an OPEN claim: it keeps the rest
     * of its amount and loses any figure a statement bound it to; a new OPEN claim with the split-off
     * amount, the same date and note, the next number, opened by the user and due by a date fixed now
     * from the terms in force on the sheet's today (the day it is created), names the original's number as the one it was split from;
     * it is a late entry when that due-by is inside a soft-closed period. Refused when the claim is not
     * OPEN, is deferred on the sheet's today, or when its due-by or the new claim's is inside a closed
     * period.
     */
    @Transactional
    public Split split(String sheetId, String user, long claimId, BigDecimal amount) {
        Sheet sheet = sheets.lock(sheetId);
        LocalDate today = sheet.getToday();
        Rollups.Rollup before = rollups.take(sheetId);
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be split; only an OPEN one can.");
        }
        if (claim.isDeferredOn(today)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is deferred until " + claim.getDeferredUntil() + ": it cannot be split.");
        }
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        BigDecimal off = money(amount);
        if (off.signum() <= 0 || off.compareTo(claim.getAmount()) >= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The amount split off must be above 0.00 and below the claim's " + claim.getAmount().toPlainString() + ".");
        }
        Person person = person(sheetId, claim.getPersonId());
        int terms = terms(sheet, person, today);
        LocalDate dueBy = holidays.dueBy(sheetId, claim.getDate(), terms);
        periods.refuseIfClosed(sheetId, dueBy);
        Claim split = claim.split(off, terms, dueBy);
        if (periods.softClosedOn(sheetId, dueBy)) {
            split.markLateEntry();
        }
        split.number(periods.nextClaimNumber(sheetId, split.getDueBy()));
        split.openedBy(user);
        Claim original = claims.saveAndFlush(claim);
        split = logged(claims.saveAndFlush(split), user, person);
        audited("CLAIM_SPLIT", sheetId, user, person, original, original.getNumber(), " into=" + split.getNumber());
        audited("CLAIM_OPENED", sheetId, user, person, split);
        rollups.audit(sheetId, user, before);
        return new Split(original, split);
    }

    /**
     * Part-settle an OPEN claim in one transaction: refused when settling it would be refused (see
     * {@link #refuseSettling}); else the amount is split off it and the new claim settled. A refusal
     * at any step writes nothing: the split is rolled back with it.
     */
    @Transactional
    public Split settlePart(String sheetId, String user, long claimId, BigDecimal amount) {
        refuseSettling(sheetId, user, claimId);
        Split split = split(sheetId, user, claimId, amount);
        Claim settled = move(sheetId, user, split.split().getId(), ClaimStatus.SETTLED);
        return new Split(split.original(), settled);
    }

    /**
     * Refuse when settling an OPEN claim would be refused: the user's role may not settle, the claim
     * is not OPEN, is due by inside a closed period, is deferred on the sheet's today, was opened or
     * last edited by the user, or its due is above the proof threshold with no proof. Taken before a
     * part-settle splits the claim, so a part-settle such a settle would refuse writes nothing.
     */
    @Transactional(readOnly = true)
    public void refuseSettling(String sheetId, String user, long claimId) {
        LocalDate today = sheets.load(sheetId).getToday();
        Claim claim = claim(sheetId, claimId);
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        if (!roles.permits(sheetId, user, "settle")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not settle a claim.");
        }
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be part-settled; only an OPEN one can.");
        }
        if (claim.isDeferredOn(today)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is deferred until " + claim.getDeferredUntil() + ": it cannot be settled before then.");
        }
        if (claim.isOpenedOrEditedBy(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The user who opened or last edited a claim cannot settle it.");
        }
        Person person = person(sheetId, claim.getPersonId());
        String grade = person.gradeOn(claim.getDate());
        refuseUnproved(sheetId, claim, grade, dues.settling(claim, grade));
    }

    /**
     * The PENDING claim a steward approves or rejects; refused when it is not PENDING, when its date
     * is inside a closed period, or when the user is not a steward.
     */
    private Claim pending(String sheetId, String user, long claimId) {
        Claim claim = claim(sheetId, claimId);
        periods.refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim is not pending approval.");
        }
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward approves or rejects a claim.");
        }
        return claim;
    }

    /**
     * Refuse settling or approving a claim whose due is above the proof threshold in force on the
     * claim's date (its person's grade's, the grade in force then, when one is set, else the sheet's; a
     * due of n/a is within any threshold) with no proofs, or, while the sheet has proof kinds, with no
     * proof of one of them.
     */
    private void refuseUnproved(String sheetId, Claim claim, String grade, BigDecimal due) {
        Sheet sheet = sheets.load(sheetId);
        BigDecimal threshold = sheet.proofThresholdFor(grade, claim.getDate());
        if (threshold == null || due == null || due.compareTo(threshold) <= 0) {
            return;
        }
        if (claim.getProofs() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim's due is above the proof threshold of " + threshold.toPlainString()
                            + ": it needs a proof before it is settled.");
        }
        for (String kind : sheet.proofKindList()) {
            if (claim.getProofs(kind) == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The claim's due is above the proof threshold of " + threshold.toPlainString()
                                + ": it needs a " + kind + " proof before it is settled.");
            }
        }
    }

    /**
     * Refuse opening a claim with an empty note whose due is above the sheet's note threshold in force
     * on the claim's date (a due of n/a is within any threshold).
     */
    private void refuseUnnoted(String sheetId, Claim claim, BigDecimal due) {
        BigDecimal threshold = sheets.load(sheetId).noteThresholdOn(claim.getDate());
        if ((claim.getNote() == null || claim.getNote().isBlank()) && threshold != null && due != null
                && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim's due is above the note threshold of " + threshold.toPlainString()
                            + ": it needs a note before it is opened.");
        }
    }

    /**
     * Refuse voiding a SETTLED claim unless the user acts as a steward and the sheet's today is no more
     * than {@link ClaimStatus#VOID_SETTLED_DAYS} days after the claim's settlement in its status log.
     */
    private void refuseVoidingSettled(String sheetId, String user, Claim claim, LocalDate today) {
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward voids a SETTLED claim.");
        }
        LocalDate settledOn = claim.settledOn();
        if (settledOn == null || today == null || today.isAfter(settledOn.plusDays(ClaimStatus.VOID_SETTLED_DAYS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A SETTLED claim can only be voided within "
                    + ClaimStatus.VOID_SETTLED_DAYS + " days of its settlement.");
        }
    }

    /** Whether a claim's due is above the sheet's review threshold in force on the claim's date. */
    private boolean overReviewThreshold(String sheetId, Claim claim, BigDecimal due) {
        BigDecimal threshold = sheets.load(sheetId).reviewThresholdOn(claim.getDate());
        return threshold != null && due != null && due.compareTo(threshold) > 0;
    }

    /**
     * Whether a clerk is settling a claim whose due is above the clerk limit in force on the sheet's
     * today: its person's grade's when one is set, else the sheet's.
     */
    private boolean overClerkLimit(String sheetId, String user, String grade, BigDecimal due) {
        BigDecimal limit = sheets.clerkLimit(sheetId, grade);
        if (limit == null || due == null || due.compareTo(limit) <= 0) {
            return false;
        }
        return Role.CLERK.equals(roles.roleOf(sheetId, user));
    }

    /**
     * Refuse a correction (an amount below 0.00) unless it names, by number, a SETTLED claim of the
     * same person, and unless it is no more than what remains of that claim: its amount less the
     * amounts of the corrections already made against it (VOID or DELETED ones, and the claim
     * {@code self} itself, left out); and refuse naming a claim to correct on any other claim.
     */
    private void refuseUnlessCorrects(String sheetId, long personId, BigDecimal amount, String corrects, Long self) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only a correction, with an amount below 0.00, names a claim it corrects.");
            }
            return;
        }
        Claim target = corrects == null ? null : claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .filter(corrected -> corrected.getStatus() == ClaimStatus.SETTLED)
                .orElse(null);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A correction must name the number of a SETTLED claim of the same person.");
        }
        BigDecimal remains = target.getAmount();
        for (Claim made : claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId)) {
            if (corrects.equals(made.getCorrects()) && !made.getId().equals(self)
                    && made.getStatus() != ClaimStatus.VOID && made.getStatus() != ClaimStatus.DELETED) {
                remains = remains.add(made.getAmount());
            }
        }
        if (money(amount).negate().compareTo(remains) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A correction may not exceed what remains of " + corrects + ": "
                            + money(remains.max(BigDecimal.ZERO)).toPlainString() + ".");
        }
    }

    /** Refuse a claim dated after its person's retirement, or after the from and before the to of a past one. */
    private static void refuseIfRetired(Person person, LocalDate date) {
        if (!person.canClaimOn(date)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " is retired from " + person.getRetiredFrom() + ": a claim cannot be dated after it.");
        }
        Retirement past = person.pastRetirementBarring(date);
        if (past != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, person.getName() + " was retired from "
                    + past.getFromDate() + " to " + past.getToDate() + ": a claim cannot be dated within it.");
        }
    }

    /**
     * The claim's timeline: the audit lines about it, newest first, as the file holds them — those
     * carrying its number, or for a claim with no number those naming its person, amount and date.
     */
    @Transactional(readOnly = true)
    public List<String> timeline(String sheetId, long claimId) {
        Claim claim = claim(sheetId, claimId);
        Person person = person(sheetId, claim.getPersonId());
        return audit.linesOfClaim(sheetId, claim.getNumber(), person.getName(),
                claim.getAmount().toPlainString(), claim.getDate().toString());
    }

    private Claim claim(String sheetId, long claimId) {
        return claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
    }

    /**
     * The terms, in business days, a claim of the person created on a day (the sheet's today then) is fixed
     * with, whatever its date: the first of the person's own terms, their grade's terms and the sheet's terms
     * in force on that day.
     */
    private static int terms(Sheet sheet, Person person, LocalDate date) {
        Integer own = person.termsOn(date);
        return own != null ? own : sheet.termsFor(person.gradeOn(date), date);
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
    }

    /**
     * Add a line to the claim's status log for the status it now has: by the user, on the sheet's
     * today, with its due as computed now.
     */
    private Claim logged(Claim claim, String user, Person person) {
        claim.logged(user, sheets.today(claim.getSheetId()), dues.due(claim, person.gradeOn(claim.getDate())));
        return claims.saveAndFlush(claim);
    }

    /** Record the change to the claim once it is committed; a claim with a number carries it. */
    private void audited(String event, String sheetId, String user, Person person, Claim claim) {
        audited(event, sheetId, user, person, claim, claim.getNumber(), "");
    }

    private void audited(String event, String sheetId, String user, Person person, Claim claim, String number,
            String suffix) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "") + suffix);
    }

    /** The due figure a claim shows, without its late charge (see {@link Dues#figure}). */
    public BigDecimal figure(Claim claim, String grade) {
        return dues.figure(claim, grade);
    }

    /** The due a claim shows, with its late charge (see {@link Dues#due}). */
    public BigDecimal due(Claim claim, String grade) {
        return dues.due(claim, grade);
    }

    /** Refuse a reference already used on the sheet by a claim other than the one given (null: any). */
    private void refuseIfReferred(String sheetId, String ref, Long self) {
        if (ref == null) {
            return;
        }
        claims.findBySheetIdAndRefKey(sheetId, Claim.keyOf(ref))
                .filter((other) -> !other.getId().equals(self))
                .ifPresent((other) -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "The reference " + ref + " is already used on this sheet.");
                });
    }

    /** A reference as typed, without surrounding spaces; null when blank. */
    static String reference(String ref) {
        return ref == null || ref.isBlank() ? null : ref.trim();
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String clean(String note) {
        return note == null ? "" : note.trim();
    }
}
