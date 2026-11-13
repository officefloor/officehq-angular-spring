package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.history.History;
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodClosedException;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonRetiredException;
import net.officefloor.hq.app.person.Statement;
import net.officefloor.hq.app.person.StatementRepository;
import net.officefloor.hq.app.person.Watch;
import net.officefloor.hq.app.person.WatchRepository;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateRepository;
import net.officefloor.hq.app.role.ActionNotAllowedException;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.voidreason.VoidReasonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/** Keeps the people's claims on each sheet. A new claim with no date is dated the sheet's entry date while
 *  set, else its today. */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final RateRepository rates;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final PeriodRepository periods;
    private final StatementRepository statements;
    private final Rollup rollup;
    private final RoleService roles;
    private final WatchRepository watches;
    private final History history;
    private final HolidayService holidays;
    private final SheetTagRepository sheetTags;
    private final VoidReasonRepository voidReasons;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateRepository rates, SheetService sheets,
            SheetRepository sheetRows, PeriodRepository periods, StatementRepository statements, Rollup rollup,
            RoleService roles, WatchRepository watches, History history, HolidayService holidays,
            SheetTagRepository sheetTags, VoidReasonRepository voidReasons, Audit audit) {
        this.sheetTags = sheetTags;
        this.voidReasons = voidReasons;
        this.history = history;
        this.holidays = holidays;
        this.watches = watches;
        this.roles = roles;
        this.sheetRows = sheetRows;
        this.periods = periods;
        this.rollup = rollup;
        this.claims = claims;
        this.people = people;
        this.rates = rates;
        this.sheets = sheets;
        this.statements = statements;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        sheets.today(sheetId);
        return claims.findBySheetIdOrderByListOrderDesc(sheetId);
    }

    /** The terms in force for a claim of the person created on the day: the person's own, else those of the
     *  grade they had that day, else the sheet's, each as they stood that day. */
    private int termsOn(String sheetId, Person person, LocalDate day) {
        Integer own = person.termsOn(day);
        return own != null ? own
                : sheetRows.findById(sheetId).orElseThrow(SheetNotFoundException::new)
                        .termsFor(person.gradeOn(day), day);
    }

    /** Add a claim; a negative amount (a correction) must name a SETTLED claim of the person by number,
     *  and a claim of 0.00 or more must name none. A reference (none: not given) already used on the sheet
     *  is refused. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, List<String> tags) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate on = date != null ? date : sheets.entryDefault(sheetId);
        int terms = termsOn(sheetId, person, sheets.today(sheetId));
        LocalDate dueBy = holidays.businessDaysAfter(sheetId, on, terms);
        refuseClosed(sheetId, dueBy);
        refuseRetired(person, on);
        refuseCorrection(sheetId, personId, amount, corrects, null);
        refuseRefUsed(sheetId, ref, null);
        Rollup.Snapshot before = rollup.take(sheetId);
        Claim claim = new Claim(sheetId, personId, amount.setScale(2), on, dueBy, note, corrects);
        claim.setTerms(terms);
        claim.setRef(ref);
        claim.setTags(tags);
        claim.setListOrder(claims.maxListOrder(sheetId) + 1);
        claim.setLateEntry(periods.softClosedOn(sheetId, dueBy));
        logStatus(claim, person, user);
        claim = claims.saveAndFlush(claim);
        audit("CLAIM_ADDED", claim, person, user, claim.getNumber(),
                (claim.getRef() != null ? " ref=" + claim.getRef() : "")
                        + (tags.isEmpty() ? "" : " tags=" + String.join(",", tags)));
        recordTags(sheetId, tags);
        if (claim.isLateEntry()) {
            audit("LATE_ENTRY", claim, person, user);
        }
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Add a DRAFT claim of 0.00 or more by the claim form's rules, as {@link #add} would; none when the form
     *  would refuse it (its period is closed, or its person retired by its date), then nothing is written. */
    @Transactional
    public Optional<Claim> addIfAccepted(String sheetId, String user, long personId, BigDecimal amount,
            LocalDate date, String note) {
        try {
            return Optional.of(add(sheetId, user, personId, amount, date, note, null, null, List.of()));
        } catch (PeriodClosedException | PersonRetiredException e) {
            return Optional.empty();
        }
    }

    /** Change a DRAFT claim; a claim in any other status is refused, and a correction or a reference as when
     *  added. A claim with lines keeps the amount they add up to: another amount is refused. */
    @Transactional
    public Claim update(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, List<String> tags) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotDraftException(claim.getStatus());
        }
        refuseLocked(claim, user);
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDueBy());
        refuseRetired(people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new), on);
        refuseCorrection(sheetId, claim.getPersonId(), amount, corrects, claim.getId());
        refuseRefUsed(sheetId, ref, claim.getId());
        if (!claim.getLines().isEmpty() && amount.compareTo(claim.getAmount()) != 0) {
            throw new ClaimLineException("A claim with lines takes its amount from its lines.");
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        claim.setAmount(amount.setScale(2));
        claim.setDate(on);
        claim.setNote(note);
        claim.setCorrects(corrects);
        claim.setRef(ref);
        claim.setTags(tags);
        claim.setLockedBy(null);
        claim.setEditedBy(user);
        Claim updated = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(updated.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        audit("CLAIM_EDITED", updated, person, user);
        recordTags(sheetId, tags);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Change the amount of a claim still DRAFT as the claim form would edit it, keeping the rest of it; none
     *  when the claim is not a DRAFT, no longer on the sheet, or the form would refuse the edit, then nothing
     *  is written. */
    @Transactional
    public Optional<Claim> changeDraftAmountIfAccepted(String sheetId, String user, long claimId,
            BigDecimal amount) {
        Optional<Claim> found = claims.findByIdAndSheetId(claimId, sheetId);
        if (found.isEmpty() || found.get().getStatus() != ClaimStatus.DRAFT) {
            return Optional.empty();
        }
        Claim claim = found.get();
        try {
            return Optional.of(update(sheetId, user, claimId, amount, claim.getDate(), claim.getNote(),
                    claim.getCorrects(), claim.getRef(), claim.getTags()));
        } catch (ClaimLockedException | PeriodClosedException | PersonRetiredException | PersonNotFoundException
                | ClaimCorrectionException | ClaimRefException | ClaimLineException e) {
            return Optional.empty();
        }
    }

    /** Open a claim's editor: the claim is locked for every other user until the user saves or cancels it,
     *  or the sheet's today changes. Refused for a claim that is not a DRAFT, a claim in a closed period, and
     *  a claim another user has locked; a refused open takes no lock. */
    @Transactional
    public Claim lock(String sheetId, String user, long claimId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotDraftException(claim.getStatus());
        }
        refuseClosed(sheetId, claim.getDueBy());
        refuseLocked(claim, user);
        claim.setLockedBy(user);
        return claims.saveAndFlush(claim);
    }

    /** Close a claim's editor (cancel): the user's lock on it is released; another user's stays. */
    @Transactional
    public Claim unlock(String sheetId, String user, long claimId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (user.equals(claim.getLockedBy())) {
            claim.setLockedBy(null);
            claim = claims.saveAndFlush(claim);
        }
        return claim;
    }

    /** Move a claim to another status; refused when the user's role may not open, settle or void it (the
     *  sheet's permission matrix), when its current status does not allow it,
     *  or when the user settling it is the one who opened it. A clerk settling a claim whose due is above
     *  the sheet's clerk limit (a due of n/a is within any limit) puts it PENDING instead, for a steward to
     *  approve or reject; its due stays live. Settling fixes the
     *  claim's due at its grade's rate in force on its date (a claim bound by a statement at the figure it
     *  bound), its late charge on the sheet's today frozen in on top; with no figure then, its due stays live. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus to) {
        return changeStatus(sheetId, user, claimId, to, "");
    }

    /** Void a claim (see changeStatus): while the sheet has void reasons, with one of them, which it keeps;
     *  a void without one, or with one not on the list, is refused. */
    @Transactional
    public Claim voidClaim(String sheetId, String user, long claimId, String reason) {
        return changeStatus(sheetId, user, claimId, ClaimStatus.VOID, reason);
    }

    /** Settle a claim (see changeStatus) as of a date on or before the sheet's today: its due is frozen as
     *  the claim stood at the end of that date, that date standing in for the sheet's today, as the as-of
     *  view works it out. Refused for a date after the sheet's today, and for a claim not on the sheet then.
     *  With no date, a settle as of today. */
    @Transactional
    public Claim settle(String sheetId, String user, long claimId, LocalDate on) {
        return changeStatus(sheetId, user, claimId, ClaimStatus.SETTLED, "", false, on);
    }

    private Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus to, String voidReason) {
        return changeStatus(sheetId, user, claimId, to, voidReason, false);
    }

    private Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus to, String voidReason,
            boolean judged) {
        return changeStatus(sheetId, user, claimId, to, voidReason, judged, null);
    }

    /** Change a claim's status; a settle whose four-eyes and proof rules were already judged on another claim
     *  (a part-settle's original) skips them; a settle as of a date freezes the due as of it. */
    private Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus to, String voidReason,
            boolean judged, LocalDate on) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        roles.require(sheetId, user, switch (to) {
            case OPEN -> "open";
            case SETTLED, PENDING -> "settle";
            case VOID -> "void";
            case DRAFT, DELETED, REVIEW -> throw new IllegalStateException();
        });
        if (!claim.getStatus().canBecome(to)) {
            throw new ClaimStatusException(claim.getStatus(), to);
        }
        BigDecimal dueThen = to == ClaimStatus.SETTLED && on != null ? dueAsOf(sheetId, claim, on) : null;
        if (to == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED) {
            refuseVoidSettled(claim, sheetId, user);
        }
        if (to == ClaimStatus.VOID) {
            refuseVoidReason(sheetId, voidReason);
        }
        String reversed = to == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED
                ? " reversed=" + show(claim.getSettledDue()) : "";
        if (to == ClaimStatus.VOID && !voidReason.isEmpty()) {
            reversed += " reason=" + voidReason;
        }
        if (to == ClaimStatus.SETTLED && !judged) {
            refuseSettler(claim, user);
        }
        if (to == ClaimStatus.SETTLED && claim.deferredOn(sheets.today(sheetId))) {
            throw new ClaimDeferException("The claim is deferred until " + claim.getDeferredUntil() + ".");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        if (to == ClaimStatus.OPEN) {
            refuseFlagged(sheetId, user, person);
            refuseNoteless(claim, shownDue(claim, person));
            refuseOverCap(claim);
            refuseOverMaxOpen(claim);
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        String number = claim.getNumber();
        if (to == ClaimStatus.OPEN && overReviewThreshold(claim, shownDue(claim, person))) {
            to = ClaimStatus.REVIEW;
            claim.setOpenedBy(user);
        } else if (to == ClaimStatus.OPEN) {
            claim.setOpenedBy(user);
        }
        if (to == ClaimStatus.SETTLED) {
            BigDecimal figure = claim.isBound() ? claim.getStatedDue() : liveDue(claim, person);
            BigDecimal due = on != null ? dueThen : figure == null ? null : charged(claim, person, figure);
            if (!judged) {
                refuseUnproved(claim, person, due);
            }
            if (overLimit(sheetId, user, person, due)) {
                to = ClaimStatus.PENDING;
            } else {
                claim.setSettledDue(due);
                if (number == null) {
                    number = sheets.nextClaimNumber(sheetId, claim.getDueBy());
                }
            }
        }
        claim.setStatus(to);
        claim.setNumber(number);
        if (to == ClaimStatus.VOID) {
            claim.setVoidReason(voidReason.isEmpty() ? null : voidReason);
        }
        logStatus(claim, person, user);
        Claim updated = claims.saveAndFlush(claim);
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case REVIEW -> "CLAIM_REVIEW";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT, DELETED -> throw new IllegalStateException();
        };
        audit(event, updated, person, user, number, to == ClaimStatus.SETTLED
                ? dueField(updated) + (on != null ? " on=" + on : "") : reversed);
        if (to == ClaimStatus.SETTLED) {
            notifyWatchers(updated, person, user);
        }
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** A claim's due as it stood at the end of a date, that date standing in for the sheet's today, as the as-of
     *  view works it out: its settled due then, else the figure its statement bound then, else its amount at the
     *  rate in force on its date of its person's grade then, late charge and floor on top (none when no rate
     *  applies). Refused for a date after the sheet's today, and a claim not on the sheet then. */
    private BigDecimal dueAsOf(String sheetId, Claim claim, LocalDate on) {
        LocalDate today = sheets.today(sheetId);
        if (on.isAfter(today)) {
            throw new ClaimSettleDateException("A claim cannot be settled as of a date after the sheet's today ("
                    + today + ").");
        }
        Claim then = history.findAsOf(Claim.class, sheetId, claim.getId(), on)
                .orElseThrow(() -> new ClaimSettleDateException("The claim was not on the sheet on " + on + "."));
        Person personThen = history.findAsOf(Person.class, sheetId, then.getPersonId(), on)
                .orElseThrow(() -> new ClaimSettleDateException("The claim was not on the sheet on " + on + "."));
        Sheet sheetOn = history.findAsOf(Sheet.class, sheetId, sheetId, on).orElseGet(() -> {
            // no change to the sheet counts by then: it stands as it was started
            Sheet now = sheetRows.findById(sheetId).orElseThrow(SheetNotFoundException::new);
            return new Sheet(sheetId, now.getCreatedBy(), now.getCreatedAt(), on);
        });
        sheetOn.setToday(on);
        Optional<Sheet> sheetThen = Optional.of(sheetOn);
        List<Rate> ratesThen = new ArrayList<>(history.listAsOf(Rate.class, sheetId, on));
        sheetRows.ancestors(sheetId).forEach(a -> ratesThen.addAll(history.listAsOf(Rate.class, a, on)));
        List<Claim> claimsThen = history.listAsOf(Claim.class, sheetId, on);
        BigDecimal figure = then.getSettledDue() != null ? then.getSettledDue()
                : then.isBound() ? then.getStatedDue()
                : Rollup.inForce(ratesThen, personThen.gradeOn(then.getDate()), then.getDate(), then)
                        .map(rate -> Rollup.rated(sheetThen, then, claimsThen, rate)).orElse(null);
        return figure == null ? null : Rollup.charged(sheetThen, then, personThen, figure);
    }

    /** Delete a DRAFT claim: it becomes DELETED, counting nowhere, and cannot be edited, opened or voided
     *  until restored. Refused for a claim in any other status, a claim in a closed period, and a claim
     *  another user's editor is open on. */
    @Transactional
    public Claim delete(String sheetId, String user, long claimId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimDeleteException("Only a DRAFT claim can be deleted; this one is " + claim.getStatus() + ".");
        }
        refuseLocked(claim, user);
        return moveDeleted(sheetId, user, claim, ClaimStatus.DELETED, "CLAIM_DELETED");
    }

    /** Restore a DELETED claim to a DRAFT. Refused for a claim that is not DELETED, a claim deleted before the
     *  sheet's today, and a claim in a closed period. */
    @Transactional
    public Claim restore(String sheetId, String user, long claimId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.DELETED) {
            throw new ClaimDeleteException("Only a DELETED claim can be restored; this one is " + claim.getStatus() + ".");
        }
        LocalDate deletedOn = claim.getStatusLog().stream()
                .filter(line -> line.getStatus() == ClaimStatus.DELETED)
                .map(StatusChange::getOn)
                .reduce((first, last) -> last)
                .orElse(null);
        if (deletedOn == null || !deletedOn.equals(sheets.today(sheetId))) {
            throw new ClaimDeleteException("A deleted claim can only be restored on the day it was deleted.");
        }
        return moveDeleted(sheetId, user, claim, ClaimStatus.DRAFT, "CLAIM_RESTORED");
    }

    /** Move a claim into or out of DELETED: log the status and write the event naming the claim. */
    private Claim moveDeleted(String sheetId, String user, Claim claim, ClaimStatus to, String event) {
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        Rollup.Snapshot before = rollup.take(sheetId);
        claim.setStatus(to);
        claim.setLockedBy(null);
        logStatus(claim, person, user);
        Claim updated = claims.saveAndFlush(claim);
        audit(event, updated, person, user);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Review a REVIEW claim: it becomes OPEN, still unnumbered until settled, keeping who opened it; writes CLAIM_REVIEWED
     *  then CLAIM_OPENED. Refused unless the claim is REVIEW and the user is a steward. */
    @Transactional
    public Claim review(String sheetId, String user, long claimId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.REVIEW) {
            throw new ClaimStatusException(claim.getStatus(), ClaimStatus.OPEN);
        }
        if (!roles.isSteward(sheetId, user)) {
            throw new ClaimApprovalException("Only a steward can review a claim.");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        Rollup.Snapshot before = rollup.take(sheetId);
        audit("CLAIM_REVIEWED", claim, person, user);
        claim.setStatus(ClaimStatus.OPEN);
        logStatus(claim, person, user);
        Claim updated = claims.saveAndFlush(claim);
        audit("CLAIM_OPENED", updated, person, user);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Approve a PENDING claim: it becomes SETTLED, its due fixed at the figure its person's last statement bound
     *  it to, else its live figure now, its late charge on
     *  the sheet's today frozen in on top (with no figure then, its due stays live). Refused unless the
     *  claim is PENDING and the user is a steward other than the one who opened it or last edited it. */
    @Transactional
    public Claim approve(String sheetId, String user, long claimId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ClaimStatusException(claim.getStatus(), ClaimStatus.SETTLED);
        }
        if (!mayApprove(sheetId, user, claim)) {
            throw new ClaimApprovalException("Only a steward or the person's approver can approve a claim.");
        }
        if (user.equals(claim.getOpenedBy())) {
            throw new ClaimApprovalException("The user who opened a claim cannot approve it.");
        }
        if (user.equals(claim.getEditedBy())) {
            throw new ClaimApprovalException("The user who last edited a claim cannot approve it.");
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        BigDecimal figure = claim.isBound() ? claim.getStatedDue() : liveDue(claim, person);
        BigDecimal due = figure == null ? null : charged(claim, person, figure);
        refuseUnproved(claim, person, due);
        claim.setSettledDue(due);
        if (claim.getNumber() == null) {
            claim.setNumber(sheets.nextClaimNumber(sheetId, claim.getDueBy()));
        }
        claim.setStatus(ClaimStatus.SETTLED);
        logStatus(claim, person, user);
        Claim updated = claims.saveAndFlush(claim);
        audit("CLAIM_APPROVED", updated, person, user, updated.getNumber(), dueField(updated));
        notifyWatchers(updated, person, user);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Raise a claim's proofs count by one, a proof of the kind (none chosen: a receipt). Refused for a SETTLED,
     *  VOID or DELETED claim, and a claim in a closed period. */
    @Transactional
    public Claim addProof(String sheetId, String user, long claimId, ProofKind kind) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                || claim.getStatus() == ClaimStatus.DELETED) {
            throw new ClaimProofException("A " + claim.getStatus() + " claim takes no proof.");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        claim.addProof(kind == null ? ProofKind.RECEIPT : kind);
        Claim updated = claims.saveAndFlush(claim);
        audit("CLAIM_PROOF_ADDED", updated, person, user, updated.getNumber(), " proofs=" + updated.getProofs()
                + (kind == null ? "" : " kind=" + kind.token()));
        return updated;
    }

    /** Remind an OPEN or PENDING claim: write REMIND and mark it reminded on the sheet's today. When its
     *  previous reminder was at least the sheet's escalation gap before today, and it was never escalated,
     *  also escalate it: write ESCALATE after REMIND. Refused for a claim in any other status, and a claim
     *  already reminded on the sheet's today. */
    @Transactional
    public Claim remind(String sheetId, String user, long claimId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (!claim.getStatus().isOpen()) {
            throw new ClaimRemindException("Only an OPEN or PENDING claim can be reminded.");
        }
        LocalDate today = sheets.today(sheetId);
        if (today.equals(claim.getRemindedOn())) {
            throw new ClaimRemindException("The claim was already reminded today.");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        Integer gap = sheetRows.findById(sheetId).map(Sheet::getEscalateAfter).orElse(null);
        LocalDate previous = claim.getRemindedOn();
        boolean escalate = gap != null && previous != null && !claim.isEscalated()
                && !previous.plusDays(gap).isAfter(today);
        claim.setRemindedOn(today);
        if (escalate) {
            claim.setEscalated(true);
        }
        Claim updated = claims.saveAndFlush(claim);
        audit("REMIND", updated, person, user);
        if (escalate) {
            audit("ESCALATE", updated, person, user);
        }
        return updated;
    }

    /** Waive an OPEN or PENDING claim's late charge: from then on it bears none; write CLAIM_WAIVED. Refused
     *  unless the user is a steward, and for a claim in any other status or already waived. */
    @Transactional
    public Claim waive(String sheetId, String user, long claimId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (!roles.isSteward(sheetId, user)) {
            throw new ClaimWaiveException("Only a steward can waive a late charge.");
        }
        if (!claim.getStatus().isOpen()) {
            throw new ClaimWaiveException("Only an OPEN or PENDING claim's late charge can be waived.");
        }
        if (claim.isWaived()) {
            throw new ClaimWaiveException("The claim's late charge is already waived.");
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        claim.setWaived(true);
        Claim updated = claims.saveAndFlush(claim);
        audit("CLAIM_WAIVED", updated, person, user);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Assign a REVIEW claim's review to a user name (trimmed); it stays assigned once the claim leaves REVIEW;
     *  write REVIEW_ASSIGNED ending user=<user>. Refused for a blank user, a claim in any other status, and
     *  anyone but a steward. */
    @Transactional
    public Claim assignReview(String sheetId, String user, long claimId, String assignee) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (!roles.isSteward(sheetId, user)) {
            throw new ClaimAssignException("Only a steward can assign a review.");
        }
        if (claim.getStatus() != ClaimStatus.REVIEW) {
            throw new ClaimAssignException("Only a REVIEW claim can be assigned.");
        }
        if (assignee == null || assignee.isBlank()) {
            throw new ClaimAssignException("Enter the user to assign the review to.");
        }
        String name = assignee.trim();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        claim.setReviewAssignee(name);
        Claim updated = claims.saveAndFlush(claim);
        audit("REVIEW_ASSIGNED", updated, person, user, updated.getNumber(), " user=" + name);
        return updated;
    }

    /** Hold an OPEN or PENDING claim, or release it when held: a hold no longer leaves the live totals;
     *  write CLAIM_HELD or CLAIM_RELEASED. Refused for a claim in any other status. */
    @Transactional
    public Claim toggleHold(String sheetId, String user, long claimId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (!claim.getStatus().isOpen()) {
            throw new ClaimHoldException("Only an OPEN or PENDING claim can be held or released.");
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        claim.setHeld(!claim.isHeld());
        Claim updated = claims.saveAndFlush(claim);
        audit(updated.isHeld() ? "CLAIM_HELD" : "CLAIM_RELEASED", updated, person, user);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Share a percent (two decimals, above 0.00) of a claim's due with another person on the sheet, who holds
     *  that part of it from then on; write CLAIM_SHARED. Refused for the claim's own person, a person already
     *  holding a share of it, shares that would add up to more than 100.00, and a SETTLED, VOID or DELETED claim. */
    @Transactional
    public Claim share(String sheetId, String user, long claimId, long withPersonId, BigDecimal share) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        ClaimStatus status = claim.getStatus();
        if (status == ClaimStatus.SETTLED || status == ClaimStatus.VOID || status == ClaimStatus.DELETED) {
            throw new ClaimShareException("A " + status + " claim cannot be shared.");
        }
        if (share.signum() <= 0 || share.stripTrailingZeros().scale() > 2) {
            throw new ClaimShareException("A share is a percent above 0.00, with two decimals.");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        Person with = people.findByIdAndSheetId(withPersonId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (with.getId().equals(person.getId())) {
            throw new ClaimShareException("The claim is already " + person.getName() + "'s.");
        }
        if (claim.shareOf(with.getId()).isPresent()) {
            throw new ClaimShareException(with.getName() + " already holds a share of the claim.");
        }
        BigDecimal total = claim.getShares().stream().map(ClaimShare::getShare).reduce(share, BigDecimal::add);
        if (total.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ClaimShareException("The claim's shares would add up to more than 100.00.");
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        BigDecimal shown = share.setScale(2);
        claim.addShare(with.getId(), shown);
        Claim updated = claims.saveAndFlush(claim);
        // A change to its shares alone may not reach the entity listener: keep its state for the as-of view.
        history.saved(updated);
        audit("CLAIM_SHARED", updated, person, user, updated.getNumber(),
                " with=" + with.getName() + " share=" + shown.toPlainString());
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Comment on a claim: keep the text with the user and the sheet's today, and write COMMENT_ADDED.
     *  Refused for an empty comment. */
    @Transactional
    public Claim comment(String sheetId, String user, long claimId, String text) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (text.isEmpty()) {
            throw new ClaimCommentException("Enter a comment.");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        claim.addComment(user, sheets.today(sheetId), text);
        Claim updated = claims.saveAndFlush(claim);
        // A change to its comments alone may not reach the entity listener: keep its state for the as-of view.
        history.saved(updated);
        audit("COMMENT_ADDED", updated, person, user, updated.getNumber(), " text=" + text);
        return updated;
    }

    /** Add a line to a DRAFT claim: its amount becomes what its lines add up to, the user counts as having
     *  edited it, and CLAIM_LINE_ADDED is written. Refused for an empty label, a quantity below 1, a unit
     *  amount below 0.00 or of more than two decimals, a claim that is not a DRAFT, a claim in a closed period,
     *  and a claim another user has locked. */
    @Transactional
    public Claim addLine(String sheetId, String user, long claimId, String label, int qty, BigDecimal unit) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (label.isEmpty()) {
            throw new ClaimLineException("Enter a label for the line.");
        }
        if (qty < 1) {
            throw new ClaimLineException("A line's quantity is a whole number of at least 1.");
        }
        if (unit.signum() < 0 || unit.stripTrailingZeros().scale() > 2) {
            throw new ClaimLineException("A line's unit amount is 0.00 or more, with two decimals.");
        }
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotDraftException(claim.getStatus());
        }
        refuseClosed(sheetId, claim.getDueBy());
        refuseLocked(claim, user);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        Rollup.Snapshot before = rollup.take(sheetId);
        BigDecimal shown = unit.setScale(2);
        claim.addLine(label, qty, shown);
        claim.setEditedBy(user);
        Claim updated = claims.saveAndFlush(claim);
        // A change to its lines alone may not reach the entity listener: keep its state for the as-of view.
        history.saved(updated);
        audit("CLAIM_LINE_ADDED", updated, person, user, updated.getNumber(),
                " label=" + label + " qty=" + qty + " unit=" + shown.toPlainString());
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Re-settle a SETTLED claim: freeze it again at the figure it would have today as an OPEN claim bound by
     *  no statement (today's rates, grades and floor, the late charge on the sheet's today on top). Refused
     *  unless the claim is SETTLED and the user is a steward, and for a claim in a closed period. */
    @Transactional
    public Claim resettle(String sheetId, String user, long claimId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        if (!roles.isSteward(sheetId, user)) {
            throw new ClaimResettleException("Only a steward can re-settle a claim.");
        }
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ClaimResettleException("Only a SETTLED claim can be re-settled.");
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        BigDecimal old = claim.getSettledDue();
        BigDecimal figure = liveDue(claim, person);
        BigDecimal due = null;
        if (figure != null) {
            Sheet sheet = sheetRows.findById(sheetId).orElseThrow(SheetNotFoundException::new);
            LocalDate lateFrom = sheet.lateFrom(claim.getDueBy(), person.gradeOn(claim.getDate()), claim.getDate());
            BigDecimal floored = sheet.floored(figure, claim.getDate());
            due = claim.isCorrection() ? figure
                    : claim.isWaived() ? floored
                    : floored.add(sheet.lateCharge(floored, lateFrom, claim.getDate()));
        }
        claim.setSettledDue(due);
        logStatus(claim, person, user);
        Claim updated = claims.saveAndFlush(claim);
        audit("CLAIM_RESETTLED", updated, person, user, updated.getNumber(),
                " old=" + show(old) + " new=" + show(due));
        notifyWatchers(updated, person, user);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Reject a PENDING claim with a reason: it goes back to OPEN, no longer in a settle batch, still bound by its person's last
     *  statement if that statement included it. Refused unless the claim is PENDING, the user is a
     *  steward and a reason is given. */
    @Transactional
    public Claim reject(String sheetId, String user, long claimId, String reason) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ClaimStatusException(claim.getStatus(), ClaimStatus.OPEN);
        }
        if (!mayApprove(sheetId, user, claim)) {
            throw new ClaimApprovalException("Only a steward or the person's approver can reject a claim.");
        }
        if (reason.isEmpty()) {
            throw new ClaimApprovalException("Give a reason to reject the claim.");
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        claim.setStatus(ClaimStatus.OPEN);
        claim.setSettleBatch(null);
        logStatus(claim, person, user);
        Claim updated = claims.saveAndFlush(claim);
        audit("CLAIM_REJECTED", updated, person, user, updated.getNumber(), " reason=" + reason);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Defer an OPEN claim to a date after the sheet's today: until the sheet's today reaches it, the claim
     *  counts in no total, is not overdue, cannot be settled and is left unbound by a statement. Refused for
     *  a claim that is not OPEN, a date not after the sheet's today, and a claim in a closed period. */
    @Transactional
    public Claim defer(String sheetId, String user, long claimId, LocalDate until) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ClaimDeferException("Only an OPEN claim can be deferred.");
        }
        if (until == null || !until.isAfter(sheets.today(sheetId))) {
            throw new ClaimDeferException("Defer the claim to a date after the sheet's today.");
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        claim.setDeferredUntil(until);
        Claim updated = claims.saveAndFlush(claim);
        audit("CLAIM_DEFERRED", updated, person, user, updated.getNumber(), " until=" + until);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Re-date an OPEN or PENDING claim. Its due-by stays as it was fixed, and with it its period and
     *  number, and a figure a statement bound it to stays bound. A missing date is the sheet's today.
     *  Refused unless the user is a steward, and for a claim in any other status, a claim in a closed
     *  period, and a date after its person's retirement. */
    @Transactional
    public Claim redate(String sheetId, String user, long claimId, LocalDate date) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDueBy());
        if (!roles.isSteward(sheetId, user)) {
            throw new ClaimRedateException("Only a steward can re-date a claim.");
        }
        if (!claim.getStatus().isOpen()) {
            throw new ClaimRedateException("Only an OPEN or PENDING claim can be re-dated.");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        refuseRetired(person, on);
        Rollup.Snapshot before = rollup.take(sheetId);
        LocalDate old = claim.getDate();
                claim.setDate(on);
        Claim updated = claims.saveAndFlush(claim);
        audit("CLAIM_REDATED", updated, person, user, updated.getNumber(), " old=" + old + " new=" + on);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Move a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its date and takes the next
     *  number in the sequence it is numbered in (its old number is never used again; a claim not yet settled has none),
     *  loses any figure a statement bound it to (live again until its new person's next statement), and is
     *  listed as its new person's newest claim. Refused unless the user's role may reassign, and for a settled or
     *  voided claim, a claim in a closed period, the claim's own person, or a person retired on its date. */
    @Transactional
    public Claim reassign(String sheetId, String user, long claimId, long toPersonId) {
        return move(sheetId, user, claimId, toPersonId, true);
    }

    /** Move a claim as a reassign does; one already the person's is refused only when {@code refuseOwn}. */
    private Claim move(String sheetId, String user, long claimId, long toPersonId, boolean refuseOwn) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        if (!roles.allows(sheetId, user, "reassign")) {
            throw new ClaimReassignException("Your role may not reassign a claim.");
        }
        if (!claim.getStatus().canMove()) {
            throw new ClaimReassignException("A " + claim.getStatus() + " claim cannot be reassigned.");
        }
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        Person to = people.findByIdAndSheetId(toPersonId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (refuseOwn && from.getId().equals(to.getId())) {
            throw new ClaimReassignException("The claim is already " + to.getName() + "'s.");
        }
        refuseRetired(to, claim.getDate());
        Rollup.Snapshot before = rollup.take(sheetId);
        claim.setPersonId(to.getId());
        claim.setStated(false, null);
        if (claim.getNumber() != null) {
            claim.setNumber(sheets.nextClaimNumber(sheetId, claim.getDueBy()));
        }
        claim.setListOrder(claims.maxListOrder(sheetId) + 1);
        Claim updated = claims.saveAndFlush(claim);
        audit("CLAIM_REASSIGNED", updated, to, user, updated.getNumber(),
                " from=" + from.getName() + " to=" + to.getName());
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** What a split did: the claim it kept, with the rest of its amount, and the claim it split off. */
    public record Split(Claim kept, Claim split) {
    }

    /** Split an amount off an OPEN claim: the claim keeps the rest of its amount and loses any figure a
     *  statement bound it to; a new OPEN claim of the person with the amount split off, the same date and
     *  note, no number until it is settled, opened by the user and due by the terms-th business day after its date (by the terms in
     *  force on it and the sheet's holidays), is
     *  listed as the sheet's newest, naming the original's number as split from. Refused unless the user's
     *  role may open a claim, the claim is OPEN and not deferred, and the amount is above 0.00 and below the
     *  claim's; and for a claim in a closed period. */
    @Transactional
    public Split split(String sheetId, String user, long claimId, BigDecimal amount) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        roles.require(sheetId, user, "open");
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ClaimSplitException("Only an OPEN claim can be split.");
        }
        LocalDate today = sheets.today(sheetId);
        if (claim.deferredOn(today)) {
            throw new ClaimSplitException("The claim is deferred until " + claim.getDeferredUntil() + ".");
        }
        BigDecimal part = amount.setScale(2);
        if (part.signum() <= 0 || part.compareTo(claim.getAmount()) >= 0) {
            throw new ClaimSplitException("Split off more than 0.00 and less than the claim's amount.");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        Rollup.Snapshot before = rollup.take(sheetId);
        claim.setAmount(claim.getAmount().subtract(part));
        claim.setStated(false, null);
        Claim kept = claims.saveAndFlush(claim);
        int terms = termsOn(sheetId, person, today);
        Claim split = new Claim(sheetId, person.getId(), part, claim.getDate(),
                holidays.businessDaysAfter(sheetId, claim.getDate(), terms),
                claim.getNote(), null);
        split.setTerms(terms);
        split.setListOrder(claims.maxListOrder(sheetId) + 1);
        split.setLateEntry(periods.softClosedOn(sheetId, split.getDueBy()));
        split.setStatus(ClaimStatus.OPEN);
        split.setOpenedBy(user);
        split.setSplitFrom(claim.getNumber());
        split.setTags(claim.getTags());
        logStatus(split, person, user);
        split = claims.saveAndFlush(split);
        audit("CLAIM_SPLIT", kept, person, user, kept.getNumber(), " into=" + (split.getNumber() == null ? "" : split.getNumber()));
        audit("CLAIM_OPENED", split, person, user);
        rollup.recordChanges(sheetId, user, before);
        return new Split(kept, split);
    }

    /** Part-settle an OPEN claim: split the amount off it as a split does, then settle the new claim as a settle
     *  by the user does (PENDING when over the clerk limit). The four-eyes rules and the proof rule are judged
     *  on the original claim, at its figure before the split; the new claim, opened by the user, is not held
     *  to them. Refused whole when the split or the settle would be. */
    @Transactional
    public Split settlePart(String sheetId, String user, long claimId, BigDecimal amount) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDueBy());
        roles.require(sheetId, user, "settle");
        if (claim.getStatus() == ClaimStatus.OPEN) {
            refuseSettler(claim, user);
            Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                    .orElseThrow(PersonNotFoundException::new);
            BigDecimal figure = claim.isBound() ? claim.getStatedDue() : liveDue(claim, person);
            refuseUnproved(claim, person, figure == null ? null : charged(claim, person, figure));
        }
        Split done = split(sheetId, user, claimId, amount);
        Claim settled = changeStatus(sheetId, user, done.split().getId(), ClaimStatus.SETTLED, "", true);
        return new Split(done.kept(), settled);
    }

    /** What a bulk open did: how many it opened and refused, and the claims it opened. */
    public record BulkOpened(int opened, int refused, List<Claim> claims) {
    }

    /** Open the chosen claims together, in list order (people in the order they were added,
     *  each person's claims newest first); a claim that cannot be opened is left as it is and counted as
     *  refused, as is one not on the sheet. */
    @Transactional
    public BulkOpened bulkOpen(String sheetId, String user, Collection<Long> claimIds) {
        Set<Long> chosen = new HashSet<>(claimIds);
        if (roles.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            return new BulkOpened(0, chosen.size(), List.of());
        }
        List<Claim> opened = new ArrayList<>();
        for (Claim claim : inListOrder(sheetId, chosen)) {
            try {
                opened.add(changeStatus(sheetId, user, claim.getId(), ClaimStatus.OPEN));
            } catch (ClaimStatusException | PeriodClosedException | ClaimNotFoundException
                    | ActionNotAllowedException | ClaimNoteException | ClaimCapException | ClaimFlaggedException e) {
                // refused: left as it is
            }
        }
        return new BulkOpened(opened.size(), chosen.size() - opened.size(), opened);
    }

    /** What a bulk settle did: how many it settled (PENDING included) and refused, the claims it changed, and
     *  the number of the batch it made (none when it settled none). */
    public record BulkSettled(int settled, int refused, List<Claim> claims, Integer batch) {
    }

    /** Settle the chosen claims together in list order (people by their position, each person's
     *  claims newest first), each as a single settle by the user would; one a clerk puts PENDING counts as
     *  settled. All or nothing: if any one cannot be settled (or is not on the sheet), none is, every one
     *  counts as refused and nothing is written. Otherwise they are the sheet's next batch, each claim taking
     *  its number, and BATCH_SETTLED follows the claims' own lines with the frozen figures of those SETTLED
     *  added up. */
    @Transactional
    public BulkSettled bulkSettle(String sheetId, String user, Collection<Long> claimIds) {
        Set<Long> chosen = new HashSet<>(claimIds);
        if (roles.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            return new BulkSettled(0, chosen.size(), List.of(), null);
        }
        List<Claim> found = inListOrder(sheetId, chosen);
        if (found.size() != chosen.size()) {
            return new BulkSettled(0, chosen.size(), List.of(), null);
        }
        List<Claim> settled = new ArrayList<>();
        for (Claim claim : found) {
            try {
                settled.add(changeStatus(sheetId, user, claim.getId(), ClaimStatus.SETTLED));
            } catch (ClaimStatusException | ClaimSettlerException | ClaimDeferException | ClaimProofException
                    | PeriodClosedException | ActionNotAllowedException
                    | ClaimNotFoundException e) {
                // refused: undo every settle made so far, and write none of their lines
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return new BulkSettled(0, chosen.size(), List.of(), null);
            }
        }
        Integer batch = null;
        if (!settled.isEmpty()) {
            batch = sheets.nextSettleBatch(sheetId);
            BigDecimal total = BigDecimal.ZERO.setScale(2);
            for (Claim claim : settled) {
                claim.setSettleBatch(batch);
                if (claim.getStatus() == ClaimStatus.SETTLED && claim.getSettledDue() != null) {
                    total = total.add(claim.getSettledDue());
                }
            }
            settled = claims.saveAllAndFlush(settled);
            audit.recordOnCommit("BATCH_SETTLED sheet=" + sheetId + " by=" + user + " batch=" + batch
                    + " count=" + settled.size() + " total=" + total.toPlainString());
        }
        return new BulkSettled(settled.size(), 0, settled, batch);
    }

    /** What a bulk reassign did: how many it moved and refused, and the claims it moved, in the order moved. */
    public record BulkReassigned(int reassigned, int refused, List<Claim> claims) {
    }

    /** Move the chosen claims to a person together in list order (people in the order they were added, each
     *  person's claims newest first), each as a single reassign by the user would; one that cannot be moved is
     *  left as it is and counted as refused, as is one not on the sheet. */
    @Transactional
    public BulkReassigned bulkReassign(String sheetId, String user, Collection<Long> claimIds, long toPersonId) {
        Set<Long> chosen = new HashSet<>(claimIds);
        if (roles.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            return new BulkReassigned(0, chosen.size(), List.of());
        }
        List<Claim> moved = new ArrayList<>();
        for (Claim claim : inListOrder(sheetId, chosen)) {
            try {
                moved.add(move(sheetId, user, claim.getId(), toPersonId, false));
            } catch (ClaimReassignException | PersonRetiredException | PersonNotFoundException
                    | PeriodClosedException | ClaimNotFoundException e) {
                // refused: left as it is
            }
        }
        return new BulkReassigned(moved.size(), chosen.size() - moved.size(), moved);
    }

    /** What a bulk re-date did: how many it re-dated and refused, and the claims it re-dated, in the order re-dated. */
    public record BulkRedated(int redated, int refused, List<Claim> claims) {
    }

    /** Re-date the chosen claims to one date together in list order (people by their position, each
     *  person's claims newest first), each as a single re-date by the user would; one that cannot be re-dated
     *  is left as it is and counted as refused, as is one not on the sheet. */
    @Transactional
    public BulkRedated bulkRedate(String sheetId, String user, Collection<Long> claimIds, LocalDate date) {
        Set<Long> chosen = new HashSet<>(claimIds);
        if (roles.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            return new BulkRedated(0, chosen.size(), List.of());
        }
        List<Claim> redated = new ArrayList<>();
        for (Claim claim : inListOrder(sheetId, chosen)) {
            try {
                redated.add(redate(sheetId, user, claim.getId(), date));
            } catch (ClaimRedateException | PersonRetiredException | PersonNotFoundException
                    | PeriodClosedException | ClaimNotFoundException e) {
                // refused: left as it is
            }
        }
        return new BulkRedated(redated.size(), chosen.size() - redated.size(), redated);
    }

    /** What a bulk void did: how many it voided and refused, and the claims it voided, in the order voided. */
    public record BulkVoided(int voided, int refused, List<Claim> claims) {
    }

    /** Void the chosen claims together in list order (people by their position, each person's claims
     *  newest first), each as a single void by the user would, with the one reason given; one that cannot be
     *  voided is left as it is and counted as refused, as is one not on the sheet. */
    @Transactional
    public BulkVoided bulkVoid(String sheetId, String user, Collection<Long> claimIds, String reason) {
        Set<Long> chosen = new HashSet<>(claimIds);
        if (roles.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            return new BulkVoided(0, chosen.size(), List.of());
        }
        List<Claim> voided = new ArrayList<>();
        for (Claim claim : inListOrder(sheetId, chosen)) {
            try {
                voided.add(changeStatus(sheetId, user, claim.getId(), ClaimStatus.VOID, reason));
            } catch (ClaimStatusException | ClaimVoidException | PeriodClosedException | ActionNotAllowedException
                    | PersonNotFoundException | ClaimNotFoundException e) {
                // refused: left as it is
            }
        }
        return new BulkVoided(voided.size(), chosen.size() - voided.size(), voided);
    }

    /** The sheet's chosen claims in list order: people by their position, each person's claims
     *  newest first. */
    private List<Claim> inListOrder(String sheetId, Set<Long> chosen) {
        Map<Long, Integer> personOrder = new HashMap<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            personOrder.put(person.getId(), personOrder.size());
        }
        return claims.findBySheetIdOrderByListOrderDesc(sheetId).stream()
                .filter(c -> chosen.contains(c.getId()))
                .sorted(Comparator.comparing((Claim c) -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE)))
                .toList();
    }

    /** A person, their statements in the order taken, and their claims newest first. */
    public record Stated(Person person, List<Statement> statements, List<Claim> claims) {
    }

    /** Take a person's statement: record their due-total now (late charges included) and how many claims
     *  count, and bind each claim that counts to its due as shown now, late charge and floor included (while bound
     *  its charge no longer follows the sheet's today; one already bound keeps its figure); a claim that does not count, is OPEN or PENDING, bound by no statement and dated within one of its person's holds (or its person's
     *  retirement date is reached), or is deferred past the sheet's today, is left unbound, as the live totals leave it out. */
    @Transactional
    public Stated statement(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate today = sheets.today(sheetId);
        List<Claim> theirs = claims.findForUpdateBySheetIdAndPersonIdOrderByListOrderDesc(sheetId, personId);
        Rollup.Snapshot before = rollup.take(sheetId);
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean figure = false;
        int counted = 0;
        for (Claim claim : theirs) {
            if (!claim.getStatus().counts() || Rollup.heldOut(claim, person, today) || claim.deferredOn(today)) {
                claim.setStated(false, null);
                continue;
            }
            BigDecimal due = shownDue(claim, person);
            if (due != null) {
                total = total.add(due);
                figure = true;
            }
            claim.setStated(true, due);
            counted++;
        }
        // the parts they hold of other people's claims count toward their due-total, as the rollup counts them
        BigDecimal held = rollup.heldParts(sheetId, personId);
        if (held != null) {
            total = total.add(held);
            figure = true;
        }
        boolean rated = Rollup.inForce(rates.findWithAncestors(sheetId, sheetRows.ancestors(sheetId)), person.gradeOn(today), today).isPresent();
        BigDecimal summed = total;
        BigDecimal cutTotal = rollup.cutTotals(sheetId).get(personId);
        BigDecimal dueTotal = rated || figure
                ? sheetRows.findById(sheetId).map(s -> s.capped(summed, cutTotal)).orElse(summed) : null;
        claims.saveAllAndFlush(theirs);
        statements.saveAndFlush(new Statement(sheetId, personId, today, dueTotal, counted));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + counted);
        rollup.recordChanges(sheetId, user, before);
        return new Stated(person, statements.findByPersonIdOrderByIdAsc(personId), theirs);
    }

    /** Take a person's statement as of a date on or before the sheet's today: the claims of theirs that
     *  counted at the end of that date (as the changes made while the sheet's today was on or before it left
     *  them; a later change does not count) are bound to their dues as shown then, late charge and floor
     *  included, that date standing
     *  in for the sheet's today in every figure (the grade and rate in force, the floor, late charges, holds,
     *  retirements and the cap); every other claim of theirs is left unbound. The statement is dated that
     *  date. Refused for a date after the sheet's today, and one before the person was on the sheet. */
    @Transactional
    public Stated statementAsOf(String sheetId, String user, long personId, LocalDate asOf) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate today = sheets.today(sheetId);
        if (asOf.isAfter(today)) {
            throw new StatementRefusedException("A statement cannot be taken as of a date after the sheet's today ("
                    + today + ").");
        }
        Person then = history.findAsOf(Person.class, sheetId, personId, asOf)
                .orElseThrow(() -> new StatementRefusedException(person.getName() + " was not on the sheet on " + asOf + "."));
        Sheet sheetThen = history.findAsOf(Sheet.class, sheetId, sheetId, asOf).orElseGet(() -> {
            // no change to the sheet counts by then: it stands as it was started
            Sheet now = sheetRows.findById(sheetId).orElseThrow(SheetNotFoundException::new);
            return new Sheet(sheetId, now.getCreatedBy(), now.getCreatedAt(), asOf);
        });
        sheetThen.setToday(asOf);
        Optional<Sheet> onDate = Optional.of(sheetThen);
        List<Rate> ratesThen = new ArrayList<>(history.listAsOf(Rate.class, sheetId, asOf));
        sheetRows.ancestors(sheetId).forEach(a -> ratesThen.addAll(history.listAsOf(Rate.class, a, asOf)));
        List<Claim> claimsThen = history.listAsOf(Claim.class, sheetId, asOf).stream()
                .filter(c -> c.getPersonId().equals(personId)).toList();
        List<Claim> theirs = claims.findForUpdateBySheetIdAndPersonIdOrderByListOrderDesc(sheetId, personId);
        Rollup.Snapshot before = rollup.take(sheetId);
        Map<Long, BigDecimal> figures = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        boolean figure = false;
        for (Claim claim : claimsThen) {
            if (!claim.getStatus().counts() || Rollup.heldOut(claim, then, asOf) || claim.deferredOn(asOf)) {
                continue;
            }
            BigDecimal due = claim.getSettledDue() != null ? claim.getSettledDue()
                    : claim.isBound() ? claim.getStatedDue()
                    : Rollup.inForce(ratesThen, then.gradeOn(claim.getDate()), claim.getDate(), claim)
                            .map(rate -> Rollup.rated(onDate, claim, claimsThen, rate)).orElse(null);
            if (due != null) {
                due = Rollup.charged(onDate, claim, then, due);
                total = total.add(due);
                figure = true;
            }
            figures.put(claim.getId(), due);
        }
        for (Claim claim : theirs) {
            claim.setStated(figures.containsKey(claim.getId()), figures.get(claim.getId()));
        }
        boolean rated = Rollup.inForce(ratesThen, then.gradeOn(asOf), asOf).isPresent();
        BigDecimal dueTotal = rated || figure ? sheetThen.capped(total, rollup.cutTotals(sheetId, asOf).get(personId)) : null;
        int counted = figures.size();
        claims.saveAllAndFlush(theirs);
        statements.saveAndFlush(new Statement(sheetId, personId, asOf, dueTotal, counted));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + counted
                + " asof=" + asOf);
        rollup.recordChanges(sheetId, user, before);
        return new Stated(person, statements.findByPersonIdOrderByIdAsc(personId), theirs);
    }

    /** Cancel a person's last statement: the claims it bound are live again (a figure an earlier statement
     *  bound is not restored), and the statement before it becomes their last. Refused unless the user is a
     *  steward and the person has a statement. */
    @Transactional
    public Stated cancelStatement(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (!roles.isSteward(sheetId, user)) {
            throw new StatementCancelException("Only a steward can cancel a statement.");
        }
        List<Claim> theirs = claims.findForUpdateBySheetIdAndPersonIdOrderByListOrderDesc(sheetId, personId);
        List<Statement> taken = statements.findByPersonIdOrderByIdAsc(personId);
        if (taken.isEmpty()) {
            throw new StatementCancelException(person.getName() + " has no statement to cancel.");
        }
        Statement last = taken.get(taken.size() - 1);
        Rollup.Snapshot before = rollup.take(sheetId);
        for (Claim claim : theirs) {
            claim.setStated(false, null);
        }
        claims.saveAllAndFlush(theirs);
        statements.delete(last);
        statements.flush();
        audit.recordOnCommit("STATEMENT_CANCELLED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (last.getDueTotal() == null ? "n/a" : last.getDueTotal().toPlainString()));
        rollup.recordChanges(sheetId, user, before);
        return new Stated(person, statements.findByPersonIdOrderByIdAsc(personId), theirs);
    }

    /** Refuse a settle by the user who opened the claim or last edited it. */
    private static void refuseSettler(Claim claim, String user) {
        if (user.equals(claim.getOpenedBy())) {
            throw new ClaimSettlerException("The user who opened a claim cannot settle it.");
        }
        if (user.equals(claim.getEditedBy())) {
            throw new ClaimSettlerException("The user who last edited a claim cannot settle it.");
        }
    }

    /** Refuse an edit of a claim whose editor another user has open. */
    private void refuseLocked(Claim claim, String user) {
        if (claim.getLockedBy() != null && !claim.getLockedBy().equals(user)) {
            throw new ClaimLockedException(claim.getLockedBy());
        }
    }

    /** Refuse a change to a claim due by a day inside a closed period (it belongs to the period containing
     *  its due-by). Locks the sheet first, so a period being
     *  closed at the same moment is seen. */
    private void refuseClosed(String sheetId, LocalDate date) {
        sheetRows.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (periods.closedOn(sheetId, date)) {
            throw new PeriodClosedException();
        }
    }

    /** Whether the user may approve or reject the claim: a steward, or the approver named on its person. */
    private boolean mayApprove(String sheetId, String user, Claim claim) {
        if (roles.isSteward(sheetId, user)) {
            return true;
        }
        return people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .map(p -> user.equals(p.getApprover())).orElse(false);
    }

    /** Refuse opening a claim of a person flagged on the sheet's today, by anyone but a steward. */
    private void refuseFlagged(String sheetId, String user, Person person) {
        if (person.flaggedOn(sheets.today(sheetId)) && !roles.isSteward(sheetId, user)) {
            throw new ClaimFlaggedException(person.getName() + " is flagged; only a steward may open their claims.");
        }
    }

    /** Refuse opening a claim that belongs to a period with a cap (the one containing its due-by) when its
     *  person already has that many OPEN or PENDING claims in the period. */
    private void refuseOverCap(Claim claim) {
        Optional<Period> period = periods.findBySheetIdOrderByFromDateAsc(claim.getSheetId()).stream()
                .filter(p -> p.contains(claim.getDueBy())).findFirst();
        if (period.isEmpty() || period.get().getCap() == null) {
            return;
        }
        Period p = period.get();
        long open = claims.countBySheetIdAndPersonIdAndStatusInAndDueByBetween(claim.getSheetId(),
                claim.getPersonId(), List.of(ClaimStatus.OPEN, ClaimStatus.PENDING), p.getFromDate(), p.getToDate());
        if (open >= p.getCap()) {
            throw new ClaimCapException("The person already has " + p.getCap() + " open claims in period "
                    + p.getName() + ".");
        }
    }

    /** Refuse opening a claim when the sheet has a maximum of open claims per person and its person already
     *  has that many OPEN or PENDING claims. */
    private void refuseOverMaxOpen(Claim claim) {
        Integer max = sheetRows.findById(claim.getSheetId()).map(Sheet::getMaxOpen).orElse(null);
        if (max == null) {
            return;
        }
        long open = claims.countBySheetIdAndPersonIdAndStatusIn(claim.getSheetId(), claim.getPersonId(),
                List.of(ClaimStatus.OPEN, ClaimStatus.PENDING));
        if (open >= max) {
            throw new ClaimCapException("The person already has " + max + " open claims.");
        }
    }

    /** Refuse a void while the sheet has void reasons, unless it is given one of them; with none on the
     *  list, a void takes no reason. */
    private void refuseVoidReason(String sheetId, String reason) {
        if (!voidReasons.existsBySheetId(sheetId)) {
            if (!reason.isEmpty()) {
                throw new ClaimVoidException("The sheet has no void reasons.");
            }
            return;
        }
        if (reason.isEmpty()) {
            throw new ClaimVoidException("Choose a reason to void the claim.");
        }
        if (!voidReasons.existsBySheetIdAndReason(sheetId, reason)) {
            throw new ClaimVoidException("\"" + reason + "\" is not a void reason on the sheet.");
        }
    }

    /** Refuse voiding a SETTLED claim unless the user is a steward and the sheet's today is within 7 days
     *  of the date its status log last records it SETTLED. */
    private void refuseVoidSettled(Claim claim, String sheetId, String user) {
        if (!roles.isSteward(sheetId, user)) {
            throw new ClaimVoidException("Only a steward can void a settled claim.");
        }
        LocalDate settledOn = claim.getStatusLog().stream()
                .filter(line -> line.getStatus() == ClaimStatus.SETTLED)
                .map(StatusChange::getOn)
                .reduce((first, last) -> last)
                .orElse(null);
        if (settledOn == null || sheets.today(sheetId).isAfter(settledOn.plusDays(7))) {
            throw new ClaimVoidException("A settled claim can only be voided within 7 days of settling it.");
        }
    }

    /** Refuse settling or approving a claim whose due is above the proof threshold in force on its date (its
     *  person's grade's then when one is set, else the sheet's) with no proof, or, while the sheet has proof
     *  kinds, without a proof of each of them; a due of n/a, or no threshold then, needs none. */
    private void refuseUnproved(Claim claim, Person person, BigDecimal due) {
        Sheet sheet = sheetRows.findById(claim.getSheetId()).orElseThrow(SheetNotFoundException::new);
        BigDecimal threshold = sheet.proofThresholdFor(person.gradeOn(claim.getDate()), claim.getDate());
        if (threshold == null || due == null || due.compareTo(threshold) <= 0) {
            return;
        }
        if (claim.getProofs() == 0) {
            throw new ClaimProofException("The claim's due is above the proof threshold: add a proof first.");
        }
        if (sheet.getProofKinds() != null) {
            for (String kind : sheet.getProofKinds().split(",")) {
                if (claim.getProofs(ProofKind.of(kind)) == 0) {
                    throw new ClaimProofException("The claim's due is above the proof threshold: add a " + kind.trim()
                            + " proof first.");
                }
            }
        }
    }

    /** Refuse a reference (none: nothing to check) another claim on the sheet than the excluded one (none:
     *  any claim) already has, compared without regard to case. */
    /** Every tag ever used on the sheet's claims, sorted. */
    @Transactional(readOnly = true)
    public List<String> tags(String sheetId) {
        sheets.today(sheetId);
        return sheetTags.findBySheetIdOrderByTagAsc(sheetId).stream().map(SheetTag::getTag).toList();
    }

    /** Keep the tags a claim now carries among those used on the sheet. */
    private void recordTags(String sheetId, List<String> tags) {
        for (String tag : tags) {
            if (!sheetTags.existsBySheetIdAndTag(sheetId, tag)) {
                sheetTags.save(new SheetTag(sheetId, tag));
            }
        }
    }

    private void refuseRefUsed(String sheetId, String ref, Long excluded) {
        if (ref != null && claims.refUsed(sheetId, ref, excluded)) {
            throw new ClaimRefException("The reference " + ref + " is already used on this sheet.");
        }
    }

    /** Whether a claim being opened goes to REVIEW: its due is above the review threshold in force on its
     *  date; a due of n/a, or no threshold then, does not. */
    private boolean overReviewThreshold(Claim claim, BigDecimal due) {
        BigDecimal threshold = sheetRows.findById(claim.getSheetId()).orElseThrow(SheetNotFoundException::new)
                .reviewThresholdOn(claim.getDate());
        return threshold != null && due != null && due.compareTo(threshold) > 0;
    }

    /** Refuse opening a claim with an empty note whose due is above the note threshold in force on its date;
     *  a due of n/a, or no threshold then, needs none. */
    private void refuseNoteless(Claim claim, BigDecimal due) {
        BigDecimal threshold = sheetRows.findById(claim.getSheetId()).orElseThrow(SheetNotFoundException::new)
                .noteThresholdOn(claim.getDate());
        if (threshold != null && due != null && due.compareTo(threshold) > 0
                && (claim.getNote() == null || claim.getNote().isBlank())) {
            throw new ClaimNoteException("The claim's due is above the note threshold: give it a note first.");
        }
    }

    /** Whether a clerk is settling a due above the clerk limit in force on the sheet's today for the person's
     *  grade then (its own when set, else the sheet's); a steward settles anything, and a due of n/a, or no
     *  limit, is within it. */
    private boolean overLimit(String sheetId, String user, Person person, BigDecimal due) {
        Sheet sheet = sheetRows.findById(sheetId).orElseThrow(SheetNotFoundException::new);
        BigDecimal limit = sheet.clerkLimitFor(person.gradeOn(sheet.getToday()));
        return limit != null && due != null && due.compareTo(limit) > 0 && !roles.isSteward(sheetId, user);
    }

    /** Refuse a negative amount that does not name a SETTLED claim of the person by number, or that exceeds
     *  what remains of it (its amount less the corrections already made against it, VOID or DELETED ones
     *  and the excluded claim left out), and a claim of 0.00 or more that names one. */
    private void refuseCorrection(String sheetId, long personId, BigDecimal amount, String corrects,
            Long excluded) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new ClaimCorrectionException("Only a negative amount corrects a claim.");
            }
            return;
        }
        if (corrects == null) {
            throw new ClaimCorrectionException(
                    "A negative amount is a correction: name the number of the settled claim it corrects.");
        }
        if (!claims.existsBySheetIdAndPersonIdAndNumberAndStatus(sheetId, personId, corrects, ClaimStatus.SETTLED)) {
            throw new ClaimCorrectionException("A correction must name a settled claim of the same person.");
        }
        BigDecimal remains = claims.findBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .orElseThrow(ClaimNotFoundException::new).getAmount();
        for (Claim made : claims.correctionsOf(sheetId, personId, corrects, excluded)) {
            if (made.getStatus() != ClaimStatus.VOID && made.getStatus() != ClaimStatus.DELETED) {
                remains = remains.add(made.getAmount());
            }
        }
        if (amount.negate().compareTo(remains) > 0) {
            throw new ClaimCorrectionException("A correction may not exceed what remains of the claim it corrects ("
                    + remains.setScale(2) + ").");
        }
    }

    /** Refuse a claim dated after its person's retirement date. */
    private static void refuseRetired(Person person, LocalDate date) {
        if (person.retiredOn(date)) {
            throw new PersonRetiredException();
        }
    }

    /** A claim's figure: the due fixed when settled, else the one its last statement bound, else its live due; none when that is n/a. */
    private BigDecimal due(Claim claim, Person person) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        return claim.isBound() ? claim.getStatedDue() : liveDue(claim, person);
    }

    /** Log the status a claim has just taken: by the user, on the sheet's today, with its due as the
     *  screen shows it now. */
    private void logStatus(Claim claim, Person person, String user) {
        claim.logStatus(user, sheets.today(claim.getSheetId()), shownDue(claim, person));
    }

    /** A claim's due as the screen shows it: fixed when settled, else the due its statement bound, else its
     *  live figure with its late charge on top; none when that is n/a. */
    private BigDecimal shownDue(Claim claim, Person person) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        BigDecimal figure = due(claim, person);
        return figure == null ? null : charged(claim, person, figure);
    }

    /** A claim's due from its figure: at least the floor in force on its date, then its late charge on the
     *  sheet's today, worked out on that floored figure, on top while it is OPEN or PENDING (a correction has neither). */
    private BigDecimal charged(Claim claim, Person person, BigDecimal figure) {
        return Rollup.charged(sheetRows.findById(claim.getSheetId()), claim, person, figure);
    }

    /** The claim's rate figure: its amount at the rate in force on its date of its person's grade in force
     *  then, before any late charge or floor; none when no rate applies. */
    private BigDecimal liveDue(Claim claim, Person person) {
        Sheet sheet = sheetRows.findById(claim.getSheetId()).orElseThrow(SheetNotFoundException::new);
        RoundingMode rounding = sheet.rounding(roundingDate(claim));
        return Rollup.inForce(rates.findWithAncestors(claim.getSheetId(), sheetRows.ancestors(claim.getSheetId())),
                        person.gradeOn(claim.getDate()), claim.getDate(), claim)
                .map(rate -> due(claim.getAmount(), rate, rounding))
                .orElse(null);
    }

    /** The date a claim's figures round by: its own, or for a correction the date of the claim it corrects. */
    private LocalDate roundingDate(Claim claim) {
        if (!claim.isCorrection()) {
            return claim.getDate();
        }
        return claims.findBySheetIdAndPersonIdAndNumber(claim.getSheetId(), claim.getPersonId(), claim.getCorrects())
                .map(Claim::getDate).orElse(claim.getDate());
    }

    /** An amount times a rate, divided by 100, rounded to cents. */
    private static BigDecimal due(BigDecimal amount, Rate rate, RoundingMode rounding) {
        return amount.multiply(rate.getRate()).movePointLeft(2).setScale(2, rounding);
    }

    /** The due a settled claim was fixed at, as its audit line ends: n/a when it has none. */
    private static String dueField(Claim claim) {
        return " due=" + (claim.getSettledDue() == null ? "n/a" : claim.getSettledDue().toPlainString());
    }

    /** A due as an audit line shows it: n/a when there is none. */
    private static String show(BigDecimal due) {
        return due == null ? "n/a" : due.toPlainString();
    }

    /** Tell each user watching the claim's person, in the order they started watching. */
    private void notifyWatchers(Claim claim, Person person, String user) {
        for (Watch watch : watches.findByPersonIdOrderByIdAsc(person.getId())) {
            audit.recordOnCommit("NOTIFY sheet=" + claim.getSheetId() + " by=" + user + " user=" + watch.getUser()
                    + " person=" + person.getName() + " number=" + claim.getNumber());
        }
    }

    /** The amount as the screen shows it (two decimals), the date YYYY-MM-DD. */
    private void audit(String event, Claim claim, Person person, String user) {
        audit(event, claim, person, user, claim.getNumber(), "");
    }

    /** A claim with a number (a voided claim keeps its own) follows its date with number=, then any extra fields. */
    private void audit(String event, Claim claim, Person person, String user, String number, String extra) {
        audit.recordOnCommit(event + " sheet=" + claim.getSheetId() + " by=" + user
                + " person=" + person.getName() + " amount=" + claim.getAmount().toPlainString()
                + " date=" + claim.getDate() + (number != null ? " number=" + number : "") + extra);
    }
}
