package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.role.SheetRole;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.voidreason.VoidReasonService;
import net.officefloor.hq.app.watch.WatchService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claims people make on a sheet. */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final Dues dues;
    private final SheetService sheets;
    private final Rollup rollup;
    private final PeriodRepository periods;
    private final RoleService roles;
    private final WatchService watches;
    private final HolidayService holidays;
    private final SheetTagRepository sheetTags;
    private final VoidReasonService voidReasons;
    private final Audit audit;

    /** How many days after its settlement a steward may still void a settled claim. */
    static final int VOID_SETTLED_DAYS = 7;

    public ClaimService(ClaimRepository claims, PersonRepository people, Dues dues, SheetService sheets,
            Rollup rollup, PeriodRepository periods, RoleService roles, WatchService watches,
            HolidayService holidays, SheetTagRepository sheetTags, VoidReasonService voidReasons, Audit audit) {
        this.claims = claims;
        this.people = people;
        this.dues = dues;
        this.sheets = sheets;
        this.rollup = rollup;
        this.periods = periods;
        this.roles = roles;
        this.watches = watches;
        this.holidays = holidays;
        this.sheetTags = sheetTags;
        this.voidReasons = voidReasons;
        this.audit = audit;
    }

    /** The claims on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDesc(sheetId);
    }

    /** Add a claim for a person; a date left out takes the sheet's entry date, or its today when none
     *  is set. Refused when the due-by it
     *  would be fixed with falls inside a closed period, or after the person's retirement day, and when a correction (a negative
     *  amount) does not name a settled claim of the person. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        return add(sheetId, user, personId, amount, date, note, null);
    }

    /** Add a claim for a person, naming the number of the claim it corrects (only a correction keeps it). */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        return add(sheetId, user, personId, amount, date, note, corrects, null);
    }

    /** Add a claim for a person with an external reference (null: none); refused as well when another
     *  claim of the sheet has the reference, comparing without regard to case. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref) {
        return add(sheetId, user, personId, amount, date, note, corrects, ref, null);
    }

    /** Add a claim for a person with tags (as {@link Tags} stores them; null: none). */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tags) {
        refuseRefUsed(sheetId, ref, null);
        LocalDate on = date != null ? date : sheets.entryDay(sheetId);
        int terms = terms(sheetId, person(sheetId, personId), on);
        LocalDate dueBy = holidays.businessDaysAfter(sheetId, on, terms);
        refuseClosed(sheetId, dueBy);
        refuseRetired(person(sheetId, personId), on);
        Claim corrected = corrected(sheetId, personId, amount, corrects);
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, personId);
            Claim draft = new Claim(sheetId, personId, amount, on, note,
                    corrected == null ? null : corrected.getNumber(), corrected == null ? null : corrected.getDate(),
                    dueBy);
            draft.setTerms(terms);
            draft.markAdded(claims.nextAdded());
            draft.setRef(ref);
            draft.setTags(tags);
            boolean late = periods.softClosedOn(sheetId, dueBy);
            if (late) {
                draft.markLateEntry();
            }
            logStatus(sheetId, user, person, draft);
            Claim claim = claims.saveAndFlush(draft);
            audit.recordOnCommit(record("CLAIM_ADDED", sheetId, user, person, claim)
                    + (ref != null ? " ref=" + ref : "") + (tags != null ? " tags=" + tags : ""));
            keepTags(sheetId, tags);
            if (late) {
                audit.recordOnCommit(record("LATE_ENTRY", sheetId, user, person, claim));
            }
            return claim;
        });
    }

    /** Change a draft claim's amount, date, note and external reference (null: none); a date left out
     *  takes the sheet's today. Refused when another claim of the sheet has the reference, and
     *  when the claim is not a draft (an open claim is changed by voiding it and adding a new one), and
     *  when the claim's due-by (which the edit leaves) falls inside a closed period, and when the new
     *  date is after the person's retirement day, and when the claim has lines and the amount is not
     *  their total (400). */
    @Transactional
    public Claim edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tags) {
        Claim claim = lock(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.DRAFT || claim.editLockedFor(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited, nor one another user is editing");
        }
        refuseRefUsed(sheetId, ref, claim.getId());
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDueBy());
        refuseRetired(person(sheetId, claim.getPersonId()), on);
        if (!claim.getLines().isEmpty() && amount.compareTo(claim.getAmount()) != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A claim with lines has their total as its amount");
        }
        Claim corrected = corrected(sheetId, claim.getPersonId(), amount, corrects);
        return rollup.tracking(sheetId, user, () -> {
            claim.setAmount(amount);
            claim.setCorrects(corrected == null ? null : corrected.getNumber());
            claim.setCorrectedDate(corrected == null ? null : corrected.getDate());
            claim.setDate(on);
            claim.setNote(note);
            claim.setRef(ref);
            claim.setTags(tags);
            claim.setEditLockedBy(null);
            claim.setEditedBy(user);
            keepTags(sheetId, tags);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_EDITED", sheetId, user, person(sheetId, saved.getPersonId()), saved));
            return saved;
        });
    }

    /** Change only a draft claim's amount (the row's inline entry), keeping its date, note and the
     *  number it corrects; refused exactly as {@link #edit} refuses, and recorded as it records. */
    @Transactional
    public Claim editAmount(String sheetId, String user, long claimId, BigDecimal amount) {
        Claim claim = lock(sheetId, claimId);
        return edit(sheetId, user, claimId, amount, claim.getDate(), claim.getNote(), claim.getCorrects(),
                claim.getRef(), claim.getTags());
    }

    /** Open a claim's editor for the user, locking the claim for every other user until it is saved
     *  or cancelled or the sheet's today changes; an editor the user had open on another claim of the
     *  same person is closed. Refused when the claim is not a draft or another user has its editor
     *  open (409), and when its due-by falls inside a closed period (423). Records nothing. */
    @Transactional
    public Claim openEditor(String sheetId, String user, long claimId) {
        Claim claim = lock(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.DRAFT || claim.editLockedFor(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited, nor one another user is editing");
        }
        refuseClosed(sheetId, claim.getDueBy());
        claims.releaseOtherEditLocks(sheetId, claim.getPersonId(), user, claim.getId());
        claim.setEditLockedBy(user);
        return claims.saveAndFlush(claim);
    }

    /** Close the claim's editor the user has open (cancelled), unlocking the claim; a claim whose
     *  editor the user does not have open is left as it is. Records nothing. */
    @Transactional
    public Claim closeEditor(String sheetId, String user, long claimId) {
        Claim claim = lock(sheetId, claimId);
        if (!user.equals(claim.getEditLockedBy())) {
            return claim;
        }
        claim.setEditLockedBy(null);
        return claims.saveAndFlush(claim);
    }

    /** Move a claim to a new status; a change its current status does not allow is refused, and so
     *  is settling a claim by the user who opened or last edited it, and any change to a claim whose due-by falls inside a
     *  closed period, and any change the permission matrix does not allow the user's role. A clerk settling a claim whose due is above the sheet's clerk limit leaves it pending
     *  a steward's approval instead; but settling a claim that needs a proof and has none is refused
     *  before the clerk limit is considered. Opening a claim whose due is above the review threshold
     *  in force on its date leaves it in review instead, with no number until a steward reviews it. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus to) {
        return changeStatus(sheetId, user, claimId, to, null);
    }

    /** Number the claims a bulk settle settled (or left pending) as the sheet's next settle batch;
     *  nothing when it settled none (0). Returns the batch's number. */
    @Transactional
    public int markSettleBatch(String sheetId, List<Long> claimIds) {
        if (claimIds.isEmpty()) {
            return 0;
        }
        int batch = sheets.nextSettleBatch(sheetId);
        for (Long claimId : claimIds) {
            Claim claim = lock(sheetId, claimId);
            claim.setSettleBatch(batch);
            claims.saveAndFlush(claim);
        }
        return batch;
    }

    /** Void a claim as {@link #changeStatus} does; while the sheet has void reasons, the void needs
     *  one of them (refused without), kept on the claim and ending its record. */
    @Transactional
    public Claim voidClaim(String sheetId, String user, long claimId, String reason) {
        return changeStatus(sheetId, user, claimId, ClaimStatus.VOID, reason);
    }

    private Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus to, String reason) {
        Claim claim = lock(sheetId, claimId);
        refuseClosed(sheetId, claim.getDueBy());
        String voidReason = to == ClaimStatus.VOID ? voidReasons.required(sheetId, reason) : null;
        if (to == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED) {
            return voidSettled(sheetId, user, claim, voidReason);
        }
        if (!claim.getStatus().canBecome(to) || to == ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + to);
        }
        String action = switch (to) {
            case OPEN -> "open";
            case SETTLED -> "settle";
            default -> "void";
        };
        if (!roles.may(sheetId, user, action)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not " + action + " a claim");
        }
        if (to == ClaimStatus.OPEN) {
            refuseUnnoted(sheetId, claim);
            refuseOverCap(sheetId, claim);
        }
        if (to == ClaimStatus.SETTLED && claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim deferred until " + claim.getDeferredUntil() + " cannot be settled before then");
        }
        if (to == ClaimStatus.SETTLED && claim.openedOrEditedBy(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened or last edited a claim cannot settle it");
        }
        if (to == ClaimStatus.SETTLED) {
            refuseUnproved(sheetId, claim);
        }
        ClaimStatus becomes = to == ClaimStatus.SETTLED && overClerkLimit(sheetId, user, claim)
                ? ClaimStatus.PENDING
                : to == ClaimStatus.OPEN && overReviewThreshold(sheetId, claim) ? ClaimStatus.REVIEW : to;
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, claim.getPersonId());
            if (becomes == ClaimStatus.SETTLED) {
                // Settling fixes the due at what it is now, while the claim is still open (the figure a
                // statement bound it to, if any, and its late charge); later changes to rates, the
                // late percent and the sheet's today do not alter it.
                claim.setSettledDue(dues.figure(sheetId, person, claim));
            }
            // A void claim keeps the number it had (never reused).
            claim.setStatus(becomes);
            if (becomes == ClaimStatus.VOID) {
                claim.setVoidReason(voidReason);
            }
            if (becomes == ClaimStatus.OPEN) {
                claim.setNumber(nextNumber(sheetId, claim.getDueBy()));
            }
            if (becomes == ClaimStatus.OPEN || becomes == ClaimStatus.REVIEW) {
                claim.setOpenedBy(user);
            }
            logStatus(sheetId, user, person, claim);
            Claim saved = claims.saveAndFlush(claim);
            String event = switch (becomes) {
                case OPEN -> "CLAIM_OPENED";
                case REVIEW -> "CLAIM_REVIEW";
                case PENDING -> "CLAIM_PENDING";
                case SETTLED -> "CLAIM_SETTLED";
                case VOID -> "CLAIM_VOIDED";
                case DRAFT, DELETED -> throw new IllegalStateException("a claim is deleted or restored apart");
            };
            String line = record(event, sheetId, user, person, saved);
            if (becomes == ClaimStatus.SETTLED) {
                line += " due=" + dueText(saved.getSettledDue());
            }
            line += reasonText(voidReason);
            audit.recordOnCommit(line);
            if (becomes == ClaimStatus.SETTLED) {
                watches.notifyWatchers(sheetId, user, person, saved.getNumber());
            }
            return saved;
        });
    }

    /** Review a claim in review: it becomes open, taking its number then, and keeps who opened it.
     *  Refused unless the claim is in review (409), the user is a steward (403), its period is open,
     *  and its person is under its period's cap of open claims. */
    @Transactional
    public Claim review(String sheetId, String user, long claimId) {
        Claim claim = lock(sheetId, claimId);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a claim in REVIEW can be reviewed");
        }
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can review a claim");
        }
        refuseOverCap(sheetId, claim);
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, claim.getPersonId());
            audit.recordOnCommit(record("CLAIM_REVIEWED", sheetId, user, person, claim));
            claim.setStatus(ClaimStatus.OPEN);
            claim.setNumber(nextNumber(sheetId, claim.getDueBy()));
            logStatus(sheetId, user, person, claim);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_OPENED", sheetId, user, person, saved));
            return saved;
        });
    }

    /** Delete a draft claim: it counts nowhere, and cannot be edited, opened or voided. Refused when
     *  the claim is not a draft, or its period is closed. */
    @Transactional
    public Claim delete(String sheetId, String user, long claimId) {
        Claim claim = lock(sheetId, claimId);
        refuseClosed(sheetId, claim.getDueBy());
        if (!claim.getStatus().canBecome(ClaimStatus.DELETED) || claim.editLockedFor(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be deleted, nor one another user is editing");
        }
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, claim.getPersonId());
            claim.setStatus(ClaimStatus.DELETED);
            claim.setEditLockedBy(null);
            logStatus(sheetId, user, person, claim);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_DELETED", sheetId, user, person, saved));
            return saved;
        });
    }

    /** Restore a deleted claim to a draft. Refused when the claim is not deleted, it was deleted on
     *  another day than the sheet's today, or its period is closed. */
    @Transactional
    public Claim restore(String sheetId, String user, long claimId) {
        Claim claim = lock(sheetId, claimId);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be restored");
        }
        LocalDate deleted = claim.getLog().stream().filter(l -> l.getStatus() == ClaimStatus.DELETED)
                .map(ClaimLog::getOn).reduce((first, last) -> last).orElse(null);
        if (!sheets.today(sheetId).equals(deleted)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A deleted claim can only be restored on the day it was deleted");
        }
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, claim.getPersonId());
            claim.setStatus(ClaimStatus.DRAFT);
            logStatus(sheetId, user, person, claim);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_RESTORED", sheetId, user, person, saved));
            return saved;
        });
    }

    /** Void a settled claim: only a steward may, within {@value #VOID_SETTLED_DAYS} days of its
     *  settlement (the first SETTLED line of its status log) by the sheet's today. It keeps its
     *  number and settled due, which leaves every total; the record ends with the figure reversed. */
    private Claim voidSettled(String sheetId, String user, Claim claim, String voidReason) {
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can void a settled claim");
        }
        LocalDate today = sheets.today(sheetId);
        LocalDate settled = claim.getLog().stream().filter(l -> l.getStatus() == ClaimStatus.SETTLED)
                .map(ClaimLog::getOn).findFirst().orElse(null);
        if (settled == null || today.isBefore(settled) || today.isAfter(settled.plusDays(VOID_SETTLED_DAYS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A settled claim can only be voided within " + VOID_SETTLED_DAYS + " days of its settlement");
        }
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, claim.getPersonId());
            BigDecimal reversed = dues.figure(sheetId, person, claim);
            claim.setStatus(ClaimStatus.VOID);
            claim.setVoidReason(voidReason);
            logStatus(sheetId, user, person, claim);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_VOIDED", sheetId, user, person, saved) + " reversed=" + dueText(reversed)
                    + reasonText(voidReason));
            return saved;
        });
    }

    /** Defer an open claim until a day after the sheet's today: until the sheet's today reaches it,
     *  the claim counts in no total, is not overdue, cannot be settled and a statement leaves it
     *  unbound. Refused when the claim's period is closed, the claim is not open, or the day is not
     *  after the sheet's today. */
    @Transactional
    public Claim defer(String sheetId, String user, long claimId, LocalDate until) {
        Claim claim = lock(sheetId, claimId);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be deferred");
        }
        if (!until.isAfter(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A claim can only be deferred to a date after the sheet's today");
        }
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, claim.getPersonId());
            claim.deferUntil(until);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_DEFERRED", sheetId, user, person, saved) + " until=" + until);
            return saved;
        });
    }

    /** Raise a claim's proofs count by one. Refused when the claim is settled or void, or its period
     *  is closed. It changes no figure. */
    @Transactional
    public Claim addProof(String sheetId, String user, long claimId) {
        Claim claim = lock(sheetId, claimId);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                || claim.getStatus() == ClaimStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be given a proof");
        }
        Person person = person(sheetId, claim.getPersonId());
        claim.addProof(sheets.today(sheetId));
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_PROOF_ADDED", sheetId, user, person, saved) + " proofs=" + saved.getProofs());
        return saved;
    }

    /** Remind an open or pending claim: it is reminded on the sheet's today. Refused when the claim
     *  is in any other status, and when it was already reminded on the sheet's today. It changes no
     *  figure. */
    @Transactional
    public Claim remind(String sheetId, String user, long claimId) {
        Claim claim = lock(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reminded");
        }
        LocalDate today = sheets.today(sheetId);
        if (today.equals(claim.getRemindedOn())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "The claim was already reminded today");
        }
        Person person = person(sheetId, claim.getPersonId());
        claim.remindOn(today);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("REMIND", sheetId, user, person, saved));
        return saved;
    }

    /** Comment a claim, in any status: the comment is kept with the user and the sheet's today. Refused
     *  when the text is empty. It changes no figure. */
    @Transactional
    public Claim comment(String sheetId, String user, long claimId, String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A comment cannot be empty");
        }
        Claim claim = lock(sheetId, claimId);
        Person person = person(sheetId, claim.getPersonId());
        claim.comment(user, sheets.today(sheetId), trimmed);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("COMMENT_ADDED", sheetId, user, person, saved) + " text=" + trimmed);
        return saved;
    }

    /** Add a line to a draft claim: its amount becomes the lines' quantities times unit amounts added
     *  up. Refused when the label is empty, the quantity is below 1 or the unit amount below 0.01
     *  (400), when the claim is not a draft or another user has its editor open (409), and when its
     *  due-by falls inside a closed period. Counts as editing the claim. */
    @Transactional
    public Claim addLine(String sheetId, String user, long claimId, String label, int qty, BigDecimal unit) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty() || qty < 1 || unit.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A line needs a label, a quantity of at least 1 and a unit amount above 0.00");
        }
        Claim claim = lock(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.DRAFT || claim.editLockedFor(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be given a line, nor one another user is editing");
        }
        refuseClosed(sheetId, claim.getDueBy());
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, claim.getPersonId());
            claim.addLine(trimmed, qty, unit);
            claim.setEditedBy(user);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_LINE_ADDED", sheetId, user, person, saved)
                    + " label=" + trimmed + " qty=" + qty + " unit=" + unit.setScale(2).toPlainString());
            return saved;
        });
    }

    /** Approve a pending claim: it becomes settled, its due fixed at the figure it has now (its live
     *  figure and late charge). Refused unless the claim is pending, the user is a steward and is not
     *  the one who opened or last edited it, the claim's period is open, and it has a proof if it needs one. */
    @Transactional
    public Claim approve(String sheetId, String user, long claimId) {
        Claim claim = lockPending(sheetId, user, claimId);
        if (claim.openedOrEditedBy(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened or last edited a claim cannot approve it");
        }
        refuseUnproved(sheetId, claim);
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, claim.getPersonId());
            claim.setSettledDue(dues.figure(sheetId, person, claim));
            claim.setStatus(ClaimStatus.SETTLED);
            logStatus(sheetId, user, person, claim);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_APPROVED", sheetId, user, person, saved)
                    + " due=" + dueText(saved.getSettledDue()));
            watches.notifyWatchers(sheetId, user, person, saved.getNumber());
            return saved;
        });
    }

    /** Re-settle a settled claim: its due is fixed again at the figure it would have today as an open
     *  claim bound by no statement (today's rates, grades, floor and late charge). Refused unless the
     *  claim is settled, the user is a steward, and the claim's period is open. */
    @Transactional
    public Claim resettle(String sheetId, String user, long claimId) {
        Claim claim = lock(sheetId, claimId);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a SETTLED claim can be re-settled");
        }
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can re-settle a claim");
        }
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, claim.getPersonId());
            BigDecimal old = dues.figure(sheetId, person, claim);
            BigDecimal now = dues.openFigure(sheetId, person, claim);
            claim.setSettledDue(now);
            claim.logStatus(user, sheets.today(sheetId), now);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_RESETTLED", sheetId, user, person, saved)
                    + " old=" + dueText(old) + " new=" + dueText(now));
            watches.notifyWatchers(sheetId, user, person, saved.getNumber());
            return saved;
        });
    }

    /** Reject a pending claim for a reason: it is open again, bound again by its person's last
     *  statement if that statement included it. Refused unless the claim is pending, the user is a
     *  steward, and the claim's period is open. */
    @Transactional
    public Claim reject(String sheetId, String user, long claimId, String reason) {
        Claim claim = lockPending(sheetId, user, claimId);
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, claim.getPersonId());
            claim.setStatus(ClaimStatus.OPEN);
            // back to open, it is no longer settled by the batch that left it pending
            claim.setSettleBatch(null);
            logStatus(sheetId, user, person, claim);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_REJECTED", sheetId, user, person, saved) + " reason=" + reason);
            return saved;
        });
    }

    /** Move a draft, open or pending claim to another person on the sheet. It keeps its date and
     *  status, takes the next number (a draft, having none, gets none), loses any figure a statement bound it to, and is listed as its new person's newest
     *  claim. Refused unless the permission matrix allows the user's role to reassign, and when the claim is settled or void, the person
     *  is the one it is already for, the claim's period is closed, or the claim is dated after the
     *  new person's retirement day. */
    @Transactional
    public Claim reassign(String sheetId, String user, long claimId, long toPersonId) {
        return reassign(sheetId, user, claimId, toPersonId, false);
    }

    /** Move a claim as {@link #reassign(String, String, long, long)} does; a bulk move ({@code
     *  inBulk}) also takes a claim that is already the person's, listing it as their newest. */
    @Transactional
    public Claim reassign(String sheetId, String user, long claimId, long toPersonId, boolean inBulk) {
        Claim claim = lock(sheetId, claimId);
        if (!roles.may(sheetId, user, "reassign")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not reassign a claim");
        }
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                || claim.getStatus() == ClaimStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reassigned");
        }
        if (!inBulk && claim.getPersonId() == toPersonId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The claim is already that person's");
        }
        refuseClosed(sheetId, claim.getDueBy());
        Person from = person(sheetId, claim.getPersonId());
        Person to = person(sheetId, toPersonId);
        refuseRetired(to, claim.getDate());
        return rollup.tracking(sheetId, user, () -> {
            claim.moveTo(to.getId(), claims.nextListed());
            if (claim.getNumber() != null) {
                // a moved claim takes the next number; its old one is never used again
                claim.setNumber(nextNumber(sheetId, claim.getDueBy()));
            }
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_REASSIGNED", sheetId, user, to, saved)
                    + " from=" + from.getName() + " to=" + to.getName());
            return saved;
        });
    }

    /** Re-date an open or pending claim: it keeps the due-by it was fixed with, and so its period and
     *  number, and any figure a statement bound it to. Refused unless the user is a steward, and when
     *  the claim is not open or pending, its period is closed, or the new date is after the person's
     *  retirement day. */
    @Transactional
    public Claim redate(String sheetId, String user, long claimId, LocalDate date) {
        Claim claim = lock(sheetId, claimId);
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can re-date a claim");
        }
        if (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-dated");
        }
        refuseClosed(sheetId, claim.getDueBy());
        refuseRetired(person(sheetId, claim.getPersonId()), date);
        LocalDate old = claim.getDate();
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, claim.getPersonId());
            claim.setDate(date);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_REDATED", sheetId, user, person, saved)
                    + " old=" + old + " new=" + date);
            return saved;
        });
    }

    /** What a split leaves:the original claim, with the rest of its amount, and the new open claim
     *  split off it. */
    public record Split(Claim original, Claim split) {
    }

    /** Split an amount (above 0.00 and below the claim's amount) off an open claim: it keeps the rest
     *  of its amount and loses any figure a statement bound it to, and a new open claim of the amount,
     *  the same date and note, the next number, opened by the user and due by the terms in force on
     *  the date, is listed as its person's newest claim. Refused when the claim is not open (or is
     *  deferred), its period is closed, the amount is out of range, or the permission matrix does not
     *  allow the user's role to open a claim. */
    @Transactional
    public Split split(String sheetId, String user, long claimId, BigDecimal part) {
        Claim claim = lock(sheetId, claimId);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.OPEN || claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only an open claim that is not deferred can be split");
        }
        if (!roles.may(sheetId, user, "open")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not open a claim");
        }
        if (part.signum() <= 0 || part.compareTo(claim.getAmount()) >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The amount split off must be above 0.00 and below the claim's amount");
        }
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, claim.getPersonId());
            int terms = terms(sheetId, person, claim.getDate());
            LocalDate dueBy = holidays.businessDaysAfter(sheetId, claim.getDate(), terms);
            Claim draft = claim.splitOff(part, nextNumber(sheetId, dueBy), user, dueBy, terms);
            logStatus(sheetId, user, person, draft);
            Claim original = claims.saveAndFlush(claim);
            Claim split = claims.saveAndFlush(draft);
            audit.recordOnCommit(record("CLAIM_SPLIT", sheetId, user, person, original) + " into=" + split.getNumber());
            audit.recordOnCommit(record("CLAIM_OPENED", sheetId, user, person, split));
            return new Split(original, split);
        });
    }

    /** The pending claim a steward approves or rejects, locked; refused when it is not pending, the
     *  user is not a steward, or its period is closed. */
    private Claim lockPending(String sheetId, String user, long claimId) {
        Claim claim = lock(sheetId, claimId);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a PENDING claim can be approved or rejected");
        }
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a steward can approve or reject a claim");
        }
        return claim;
    }

    /** Log the status the claim has now, changed by the user on the sheet's today, with its due as
     *  computed at this moment. */
    private void logStatus(String sheetId, String user, Person person, Claim claim) {
        claim.logStatus(user, sheets.today(sheetId), dues.figure(sheetId, person, claim));
    }

    private Claim lock(String sheetId, long claimId) {
        return claims.lockByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
    }

    private static String dueText(BigDecimal due) {
        return due == null ? "n/a" : due.setScale(2).toPlainString();
    }

    /** The number for a claim due by the day opened now: under period numbering, the next in its period
     *  (<period name>-<n>), or for one in no period the next X-<n>; otherwise the next on the sheet
     *  within the fiscal year of its due-by day (<FY>-<n>). */
    public String nextNumber(String sheetId, LocalDate dueBy) {
        if (!sheets.periodNumbering(sheetId)) {
            return sheets.nextClaimNumber(sheetId, dueBy);
        }
        return periods.findOn(sheetId, dueBy)
                .map(p -> {
                    periods.advanceClaimNumber(p.getId());
                    return p.getName() + "-" + periods.lastClaimNumber(p.getId());
                })
                .orElseGet(() -> "X-" + sheets.nextUnperiodNumber(sheetId));
    }

    /** The claim a claim of the amount corrects: none for an amount of 0.00 or more; for a correction
     *  (a negative amount), the one of the number given, which must be a settled claim of the person,
     *  or else it is refused. */
    private Claim corrected(String sheetId, long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            return null;
        }
        return (corrects == null ? Optional.<Claim>empty()
                : claims.findBySheetIdAndPersonIdAndNumberAndStatus(sheetId, personId, corrects, ClaimStatus.SETTLED))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A negative amount must correct a settled claim of the same person"));
    }

    /** Refuse a change to a claim due by the day when the day falls inside a closed period. */
    private void refuseClosed(String sheetId, LocalDate day) {
        if (periods.closedOn(sheetId, day)) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "That claim's period is closed");
        }
    }

    /** Whether a clerk is settling a claim whose due is above the sheet's clerk limit in force on its today; a claim with
     *  no due (n/a) is within any limit, and a steward settles anything. */
    private boolean overClerkLimit(String sheetId, String user, Claim claim) {
        BigDecimal limit = sheets.clerkLimit(sheetId);
        if (limit == null || SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            return false;
        }
        BigDecimal due = dues.figure(sheetId, person(sheetId, claim.getPersonId()), claim);
        return due != null && due.compareTo(limit) > 0;
    }

    /** Refuse settling or approving a claim with no proofs whose due is above the proof threshold in
     *  force on the claim's date: its person's grade's (on that date) when one is set, or else the
     *  sheet's; a claim with no due (n/a) and a claim with no threshold need none. */
    private void refuseUnproved(String sheetId, Claim claim) {
        if (claim.getProofs() > 0) {
            return;
        }
        Person person = person(sheetId, claim.getPersonId());
        BigDecimal threshold = sheets.proofThresholdFor(sheetId, person.gradeOn(claim.getDate()), claim.getDate());
        if (threshold == null) {
            return;
        }
        BigDecimal due = dues.figure(sheetId, person, claim);
        if (due != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "A claim whose due is above the proof threshold needs a proof first");
        }
    }

    /** Refuse opening a claim whose period caps its person's claims when the person already has
     *  that many open or pending claims due inside it; the period stays locked until the transaction
     *  ends, so two claims opened in it at once are counted one after the other. */
    private void refuseOverCap(String sheetId, Claim claim) {
        periods.findOn(sheetId, claim.getDueBy())
                .filter(p -> p.getClaimCap() != null)
                .flatMap(p -> periods.lockByIdAndSheetId(p.getId(), sheetId))
                .ifPresent(p -> {
                    if (claims.countOpenDueBetween(sheetId, claim.getPersonId(), p.getFromDate(), p.getToDate())
                            >= p.getClaimCap()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "The person already has " + p.getClaimCap() + " open claims in period " + p.getName());
                    }
                });
    }

    /** Refuse opening a claim with an empty note whose due is above the sheet's note threshold in
     *  force on the claim's date; a claim with no due (n/a) and a sheet with no threshold need none. */
    private void refuseUnnoted(String sheetId, Claim claim) {
        BigDecimal threshold = sheets.noteThreshold(sheetId, claim.getDate());
        if (threshold == null || (claim.getNote() != null && !claim.getNote().isBlank())) {
            return;
        }
        BigDecimal due = dues.figure(sheetId, person(sheetId, claim.getPersonId()), claim);
        if (due != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A claim whose due is above the note threshold needs a note before it is opened");
        }
    }

    /** Whether a claim opened now goes to review: its due is above the sheet's review threshold in
     *  force on the claim's date; a claim with no due (n/a) and a sheet with no threshold do not. */
    private boolean overReviewThreshold(String sheetId, Claim claim) {
        BigDecimal threshold = sheets.reviewThreshold(sheetId, claim.getDate());
        if (threshold == null) {
            return false;
        }
        BigDecimal due = dues.figure(sheetId, person(sheetId, claim.getPersonId()), claim);
        return due != null && due.compareTo(threshold) > 0;
    }

    /** Refuse a claim of the person dated after the day they are retired from, or after the from-date
     *  and before the to-date of one of their past retirements. */
    private static void refuseRetired(Person person, LocalDate day) {
        if ((person.getRetiredFrom() != null && day.isAfter(person.getRetiredFrom()))
                || person.isWithinPastRetirement(day)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A claim cannot be dated after the person's retirement");
        }
    }

    /** The terms a claim of the person dated on the day has its due-by fixed with (the terms-th
     *  business day after its date, skipping every Saturday, Sunday and holiday of the sheet): the
     *  first of the person's own terms, their grade's and the sheet's, each as in force on the day. */
    private int terms(String sheetId, Person person, LocalDate on) {
        Integer own = person.getTerms();
        return own != null ? own : sheets.termsFor(sheetId, person.gradeOn(on), on);
    }

    /** Refuse a reference (null: none) another claim of the sheet than the one given (null: any) has,
     *  comparing without regard to case. */
    /** The tags ever used on the sheet, in order. */
    @Transactional(readOnly = true)
    public List<String> tags(String sheetId) {
        return sheetTags.findBySheetIdOrderByTagAsc(sheetId).stream().map(SheetTag::getTag).toList();
    }

    /** Keep each of the tags (as {@link Tags} stores them) as used on the sheet. */
    public void keepTags(String sheetId, String tags) {
        for (String tag : Tags.of(tags)) {
            if (!sheetTags.existsBySheetIdAndTag(sheetId, tag)) {
                sheetTags.save(new SheetTag(sheetId, tag));
            }
        }
    }

    public void refuseRefUsed(String sheetId, String ref, Long exceptId) {
        if (ref != null && claims.refUsed(sheetId, ref, exceptId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The reference \"" + ref + "\" is already used on this sheet");
        }
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
    }

    /** An audit record about a claim; one that has a number carries it at the end. */
    /** The end of a void's record: its reason, when one was given. */
    private static String reasonText(String voidReason) {
        return voidReason == null ? "" : " reason=" + voidReason;
    }

    private static String record(String event, String sheetId, String user, Person person, Claim claim) {
        String number = claim.getNumber();
        return event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "");
    }
}
