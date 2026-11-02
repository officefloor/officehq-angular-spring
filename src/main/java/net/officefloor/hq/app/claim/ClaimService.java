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
    private final Audit audit;

    /** How many days after its settlement a steward may still void a settled claim. */
    static final int VOID_SETTLED_DAYS = 7;

    public ClaimService(ClaimRepository claims, PersonRepository people, Dues dues, SheetService sheets,
            Rollup rollup, PeriodRepository periods, RoleService roles, WatchService watches,
            HolidayService holidays, Audit audit) {
        this.claims = claims;
        this.people = people;
        this.dues = dues;
        this.sheets = sheets;
        this.rollup = rollup;
        this.periods = periods;
        this.roles = roles;
        this.watches = watches;
        this.holidays = holidays;
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
        LocalDate on = date != null ? date : sheets.entryDay(sheetId);
        LocalDate dueBy = dueBy(sheetId, person(sheetId, personId), on);
        refuseClosed(sheetId, dueBy);
        refuseRetired(person(sheetId, personId), on);
        Claim corrected = corrected(sheetId, personId, amount, corrects);
        return rollup.tracking(sheetId, user, () -> {
            Person person = person(sheetId, personId);
            Claim draft = new Claim(sheetId, personId, amount, on, note,
                    corrected == null ? null : corrected.getNumber(), corrected == null ? null : corrected.getDate(),
                    dueBy);
            boolean late = periods.softClosedOn(sheetId, dueBy);
            if (late) {
                draft.markLateEntry();
            }
            logStatus(sheetId, user, person, draft);
            Claim claim = claims.saveAndFlush(draft);
            audit.recordOnCommit(record("CLAIM_ADDED", sheetId, user, person, claim));
            if (late) {
                audit.recordOnCommit(record("LATE_ENTRY", sheetId, user, person, claim));
            }
            return claim;
        });
    }

    /** Change a draft claim's amount, date and note; a date left out takes the sheet's today. Refused
     *  when the claim is not a draft (an open claim is changed by voiding it and adding a new one), and
     *  when the claim's due-by (which the edit leaves) falls inside a closed period, and when the new
     *  date is after the person's retirement day. */
    @Transactional
    public Claim edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Claim claim = lock(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.DRAFT || claim.editLockedFor(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited, nor one another user is editing");
        }
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDueBy());
        refuseRetired(person(sheetId, claim.getPersonId()), on);
        Claim corrected = corrected(sheetId, claim.getPersonId(), amount, corrects);
        return rollup.tracking(sheetId, user, () -> {
            claim.setAmount(amount);
            claim.setCorrects(corrected == null ? null : corrected.getNumber());
            claim.setCorrectedDate(corrected == null ? null : corrected.getDate());
            claim.setDate(on);
            claim.setNote(note);
            claim.setEditLockedBy(null);
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
        return edit(sheetId, user, claimId, amount, claim.getDate(), claim.getNote(), claim.getCorrects());
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
     *  is settling a claim by the user who opened it, and any change to a claim whose due-by falls inside a
     *  closed period, and any change the permission matrix does not allow the user's role. A clerk settling a claim whose due is above the sheet's clerk limit leaves it pending
     *  a steward's approval instead; but settling a claim that needs a proof and has none is refused
     *  before the clerk limit is considered. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus to) {
        Claim claim = lock(sheetId, claimId);
        refuseClosed(sheetId, claim.getDueBy());
        if (to == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED) {
            return voidSettled(sheetId, user, claim);
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
        }
        if (to == ClaimStatus.SETTLED && claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim deferred until " + claim.getDeferredUntil() + " cannot be settled before then");
        }
        if (to == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot settle it");
        }
        if (to == ClaimStatus.SETTLED) {
            refuseUnproved(sheetId, claim);
        }
        ClaimStatus becomes = to == ClaimStatus.SETTLED && overClerkLimit(sheetId, user, claim)
                ? ClaimStatus.PENDING : to;
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
            if (becomes == ClaimStatus.OPEN) {
                claim.setNumber(nextNumber(sheetId, claim.getDueBy()));
                claim.setOpenedBy(user);
            }
            logStatus(sheetId, user, person, claim);
            Claim saved = claims.saveAndFlush(claim);
            String event = switch (becomes) {
                case OPEN -> "CLAIM_OPENED";
                case PENDING -> "CLAIM_PENDING";
                case SETTLED -> "CLAIM_SETTLED";
                case VOID -> "CLAIM_VOIDED";
                case DRAFT -> throw new IllegalStateException("a claim never returns to DRAFT");
            };
            String line = record(event, sheetId, user, person, saved);
            if (becomes == ClaimStatus.SETTLED) {
                line += " due=" + dueText(saved.getSettledDue());
            }
            audit.recordOnCommit(line);
            if (becomes == ClaimStatus.SETTLED) {
                watches.notifyWatchers(sheetId, user, person, saved.getNumber());
            }
            return saved;
        });
    }

    /** Void a settled claim: only a steward may, within {@value #VOID_SETTLED_DAYS} days of its
     *  settlement (the first SETTLED line of its status log) by the sheet's today. It keeps its
     *  number and settled due, which leaves every total; the record ends with the figure reversed. */
    private Claim voidSettled(String sheetId, String user, Claim claim) {
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
            logStatus(sheetId, user, person, claim);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_VOIDED", sheetId, user, person, saved) + " reversed=" + dueText(reversed));
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
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be given a proof");
        }
        Person person = person(sheetId, claim.getPersonId());
        claim.addProof();
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

    /** Approve a pending claim: it becomes settled, its due fixed at the figure it has now (its live
     *  figure and late charge). Refused unless the claim is pending, the user is a steward and is not
     *  the one who opened it, the claim's period is open, and it has a proof if it needs one. */
    @Transactional
    public Claim approve(String sheetId, String user, long claimId) {
        Claim claim = lockPending(sheetId, user, claimId);
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot approve it");
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
            logStatus(sheetId, user, person, claim);
            Claim saved = claims.saveAndFlush(claim);
            audit.recordOnCommit(record("CLAIM_REJECTED", sheetId, user, person, saved) + " reason=" + reason);
            return saved;
        });
    }

    /** Move a draft, open or pending claim to another person on the sheet. It keeps its number, date
     *  and status, loses any figure a statement bound it to, and is listed as its new person's newest
     *  claim. Refused unless the permission matrix allows the user's role to reassign, and when the claim is settled or void, the person
     *  is the one it is already for, the claim's period is closed, or the claim is dated after the
     *  new person's retirement day. */
    @Transactional
    public Claim reassign(String sheetId, String user, long claimId, long toPersonId) {
        Claim claim = lock(sheetId, claimId);
        if (!roles.may(sheetId, user, "reassign")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not reassign a claim");
        }
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reassigned");
        }
        if (claim.getPersonId() == toPersonId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The claim is already that person's");
        }
        refuseClosed(sheetId, claim.getDueBy());
        Person from = person(sheetId, claim.getPersonId());
        Person to = person(sheetId, toPersonId);
        refuseRetired(to, claim.getDate());
        return rollup.tracking(sheetId, user, () -> {
            claim.moveTo(to.getId(), claims.nextListed());
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
            LocalDate dueBy = dueBy(sheetId, person, claim.getDate());
            Claim draft = claim.splitOff(part, nextNumber(sheetId, dueBy), user, dueBy);
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
     *  (<period name>-<n>), or for one in no period the next X-<n>; otherwise the next on the sheet. */
    private String nextNumber(String sheetId, LocalDate dueBy) {
        if (!sheets.periodNumbering(sheetId)) {
            return String.valueOf(sheets.nextClaimNumber(sheetId));
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

    /** Refuse settling or approving a claim with no proofs whose due is above the sheet's proof
     *  threshold in force on the claim's date; a claim with no due (n/a) and a sheet with no threshold
     *  need none. */
    private void refuseUnproved(String sheetId, Claim claim) {
        BigDecimal threshold = sheets.proofThreshold(sheetId, claim.getDate());
        if (threshold == null || claim.getProofs() > 0) {
            return;
        }
        BigDecimal due = dues.figure(sheetId, person(sheetId, claim.getPersonId()), claim);
        if (due != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                    "A claim whose due is above the proof threshold needs a proof first");
        }
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

    /** Refuse a claim of the person dated after the day they are retired from. */
    private static void refuseRetired(Person person, LocalDate day) {
        if (person.getRetiredFrom() != null && day.isAfter(person.getRetiredFrom())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A claim cannot be dated after the person's retirement");
        }
    }

    /** The day a claim of the person dated on the day is due by: the terms-th business day after its
     *  date (skipping every Saturday, Sunday and holiday of the sheet), by the first of the person's
     *  own terms, their grade's and the sheet's, each as in force on the day. */
    private LocalDate dueBy(String sheetId, Person person, LocalDate on) {
        Integer own = person.getTerms();
        return holidays.businessDaysAfter(sheetId, on,
                own != null ? own : sheets.termsFor(sheetId, person.gradeOn(on), on));
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
    }

    /** An audit record about a claim; one that has a number carries it at the end. */
    private static String record(String event, String sheetId, String user, Person person, Claim claim) {
        String number = claim.getNumber();
        return event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate()
                + (number != null ? " number=" + number : "");
    }
}
