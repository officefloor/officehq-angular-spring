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
import java.util.Set;
import net.officefloor.hq.app.Audit;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the people's claims on each sheet. A claim with no date is dated the sheet's today. */
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
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, RateRepository rates, SheetService sheets,
            SheetRepository sheetRows, PeriodRepository periods, StatementRepository statements, Rollup rollup,
            RoleService roles, WatchRepository watches, Audit audit) {
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

    /** Add a claim; a negative amount (a correction) must name a SETTLED claim of the person by number,
     *  and a claim of 0.00 or more must name none. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, on);
        refuseRetired(person, on);
        refuseCorrection(sheetId, personId, amount, corrects);
        Rollup.Snapshot before = rollup.take(sheetId);
        int terms = person.getTerms() != null ? person.getTerms()
                : sheetRows.findById(sheetId).orElseThrow(SheetNotFoundException::new).termsFor(person.gradeOn(on), on);
        Claim claim = new Claim(sheetId, personId, amount.setScale(2), on, on.plusDays(terms), note, corrects);
        claim.setListOrder(claims.maxListOrder(sheetId) + 1);
        claim.setLateEntry(periods.softClosedOn(sheetId, on));
        logStatus(claim, person, user);
        claim = claims.saveAndFlush(claim);
        audit("CLAIM_ADDED", claim, person, user);
        if (claim.isLateEntry()) {
            audit("LATE_ENTRY", claim, person, user);
        }
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Change a DRAFT claim; a claim in any other status is refused, and a correction as when added. */
    @Transactional
    public Claim update(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ClaimNotDraftException(claim.getStatus());
        }
        refuseLocked(claim, user);
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDate());
        refuseClosed(sheetId, on);
        refuseRetired(people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new), on);
        refuseCorrection(sheetId, claim.getPersonId(), amount, corrects);
        Rollup.Snapshot before = rollup.take(sheetId);
        claim.setAmount(amount.setScale(2));
        claim.setDate(on);
        claim.setNote(note);
        claim.setCorrects(corrects);
        claim.setLockedBy(null);
        Claim updated = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(updated.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        audit("CLAIM_EDITED", updated, person, user);
        rollup.recordChanges(sheetId, user, before);
        return updated;
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
        refuseClosed(sheetId, claim.getDate());
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
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
        roles.require(sheetId, user, switch (to) {
            case OPEN -> "open";
            case SETTLED, PENDING -> "settle";
            case VOID -> "void";
            case DRAFT -> throw new IllegalStateException();
        });
        if (!claim.getStatus().canBecome(to)) {
            throw new ClaimStatusException(claim.getStatus(), to);
        }
        if (to == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED) {
            refuseVoidSettled(claim, sheetId, user);
        }
        String reversed = to == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED
                ? " reversed=" + show(claim.getSettledDue()) : "";
        if (to == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ClaimSettlerException();
        }
        if (to == ClaimStatus.SETTLED && claim.deferredOn(sheets.today(sheetId))) {
            throw new ClaimDeferException("The claim is deferred until " + claim.getDeferredUntil() + ".");
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        String number = claim.getNumber();
        if (to == ClaimStatus.OPEN) {
            number = sheets.nextClaimNumber(sheetId, claim.getDate());
            claim.setOpenedBy(user);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        if (to == ClaimStatus.SETTLED) {
            BigDecimal figure = claim.isBound() ? claim.getStatedDue() : liveDue(claim, person);
            BigDecimal due = figure == null ? null : charged(claim, figure);
            refuseUnproved(claim, due);
            if (overLimit(sheetId, user, due)) {
                to = ClaimStatus.PENDING;
            } else {
                claim.setSettledDue(due);
            }
        }
        claim.setStatus(to);
        claim.setNumber(number);
        logStatus(claim, person, user);
        Claim updated = claims.saveAndFlush(claim);
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        audit(event, updated, person, user, number, to == ClaimStatus.SETTLED ? dueField(updated) : reversed);
        if (to == ClaimStatus.SETTLED) {
            notifyWatchers(updated, person, user);
        }
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Approve a PENDING claim: it becomes SETTLED, its due fixed at its live figure now, its late charge on
     *  the sheet's today frozen in on top (with no figure then, its due stays live). Refused unless the
     *  claim is PENDING and the user is a steward other than the one who opened it. */
    @Transactional
    public Claim approve(String sheetId, String user, long claimId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ClaimStatusException(claim.getStatus(), ClaimStatus.SETTLED);
        }
        if (!roles.isSteward(sheetId, user)) {
            throw new ClaimApprovalException("Only a steward can approve a claim.");
        }
        if (user.equals(claim.getOpenedBy())) {
            throw new ClaimApprovalException("The user who opened a claim cannot approve it.");
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        BigDecimal figure = liveDue(claim, person);
        BigDecimal due = figure == null ? null : charged(claim, figure);
        refuseUnproved(claim, due);
        claim.setSettledDue(due);
        claim.setStatus(ClaimStatus.SETTLED);
        logStatus(claim, person, user);
        Claim updated = claims.saveAndFlush(claim);
        audit("CLAIM_APPROVED", updated, person, user, updated.getNumber(), dueField(updated));
        notifyWatchers(updated, person, user);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Raise a claim's proofs count by one. Refused for a SETTLED or VOID claim, and a claim in a closed
     *  period. */
    @Transactional
    public Claim addProof(String sheetId, String user, long claimId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ClaimProofException("A " + claim.getStatus() + " claim takes no proof.");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        claim.addProof();
        Claim updated = claims.saveAndFlush(claim);
        audit("CLAIM_PROOF_ADDED", updated, person, user, updated.getNumber(), " proofs=" + updated.getProofs());
        return updated;
    }

    /** Re-settle a SETTLED claim: freeze it again at the figure it would have today as an OPEN claim bound by
     *  no statement (today's rates, grades and floor, the late charge on the sheet's today on top). Refused
     *  unless the claim is SETTLED and the user is a steward, and for a claim in a closed period. */
    @Transactional
    public Claim resettle(String sheetId, String user, long claimId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
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
            due = claim.isCorrection() ? figure
                    : sheet.floored(figure.add(sheet.lateCharge(figure, claim.getDueBy(), claim.getDate())),
                            claim.getDate());
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

    /** Reject a PENDING claim with a reason: it goes back to OPEN, bound again by its person's last
     *  statement if that statement included it. Refused unless the claim is PENDING, the user is a
     *  steward and a reason is given. */
    @Transactional
    public Claim reject(String sheetId, String user, long claimId, String reason) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ClaimStatusException(claim.getStatus(), ClaimStatus.OPEN);
        }
        if (!roles.isSteward(sheetId, user)) {
            throw new ClaimApprovalException("Only a steward can reject a claim.");
        }
        if (reason.isEmpty()) {
            throw new ClaimApprovalException("Give a reason to reject the claim.");
        }
        Rollup.Snapshot before = rollup.take(sheetId);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        claim.setStatus(ClaimStatus.OPEN);
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
        refuseClosed(sheetId, claim.getDate());
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

    /** Re-date an OPEN or PENDING claim. Its due-by stays as it was fixed and a figure a statement bound
     *  it to stays bound; its period follows the new date, and while the sheet numbers by period a move
     *  into another period gives it the next number of that period's sequence. A missing date is the
     *  sheet's today. Refused unless the user is a steward, and for a claim in any other status, a claim
     *  in a closed period or a new date inside one, and a date after its person's retirement. */
    @Transactional
    public Claim redate(String sheetId, String user, long claimId, LocalDate date) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        LocalDate on = date != null ? date : sheets.today(sheetId);
        refuseClosed(sheetId, claim.getDate());
        if (!roles.isSteward(sheetId, user)) {
            throw new ClaimRedateException("Only a steward can re-date a claim.");
        }
        if (!claim.getStatus().isOpen()) {
            throw new ClaimRedateException("Only an OPEN or PENDING claim can be re-dated.");
        }
        refuseClosed(sheetId, on);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        refuseRetired(person, on);
        Rollup.Snapshot before = rollup.take(sheetId);
        LocalDate old = claim.getDate();
        claim.setNumber(sheets.redatedClaimNumber(sheetId, claim.getNumber(), old, on));
        claim.setDate(on);
        Claim updated = claims.saveAndFlush(claim);
        audit("CLAIM_REDATED", updated, person, user, updated.getNumber(), " old=" + old + " new=" + on);
        rollup.recordChanges(sheetId, user, before);
        return updated;
    }

    /** Move a DRAFT, OPEN or PENDING claim to another person on the sheet. It keeps its number and date,
     *  loses any figure a statement bound it to (live again until its new person's next statement), and is
     *  listed as its new person's newest claim. Refused unless the user's role may reassign, and for a settled or
     *  voided claim, a claim in a closed period, the claim's own person, or a person retired on its date. */
    @Transactional
    public Claim reassign(String sheetId, String user, long claimId, long toPersonId) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
        if (!roles.allows(sheetId, user, "reassign")) {
            throw new ClaimReassignException("Your role may not reassign a claim.");
        }
        if (!claim.getStatus().canMove()) {
            throw new ClaimReassignException("A " + claim.getStatus() + " claim cannot be reassigned.");
        }
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        Person to = people.findByIdAndSheetId(toPersonId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (from.getId().equals(to.getId())) {
            throw new ClaimReassignException("The claim is already " + to.getName() + "'s.");
        }
        refuseRetired(to, claim.getDate());
        Rollup.Snapshot before = rollup.take(sheetId);
        claim.setPersonId(to.getId());
        claim.setStated(false, null);
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
     *  note, the next number, opened by the user and due by its date plus the terms in force on it, is
     *  listed as the sheet's newest, naming the original's number as split from. Refused unless the user's
     *  role may open a claim, the claim is OPEN and not deferred, and the amount is above 0.00 and below the
     *  claim's; and for a claim in a closed period. */
    @Transactional
    public Split split(String sheetId, String user, long claimId, BigDecimal amount) {
        Claim claim = claims.findForUpdateByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        refuseClosed(sheetId, claim.getDate());
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
        int terms = person.getTerms() != null ? person.getTerms()
                : sheetRows.findById(sheetId).orElseThrow(SheetNotFoundException::new)
                        .termsFor(person.gradeOn(claim.getDate()), claim.getDate());
        Claim split = new Claim(sheetId, person.getId(), part, claim.getDate(), claim.getDate().plusDays(terms),
                claim.getNote(), null);
        split.setListOrder(claims.maxListOrder(sheetId) + 1);
        split.setStatus(ClaimStatus.OPEN);
        split.setNumber(sheets.nextClaimNumber(sheetId, claim.getDate()));
        split.setOpenedBy(user);
        split.setSplitFrom(claim.getNumber());
        logStatus(split, person, user);
        split = claims.saveAndFlush(split);
        audit("CLAIM_SPLIT", kept, person, user, kept.getNumber(), " into=" + split.getNumber());
        audit("CLAIM_OPENED", split, person, user);
        rollup.recordChanges(sheetId, user, before);
        return new Split(kept, split);
    }

    /** What a bulk open did: how many it opened and refused, and the claims it opened. */
    public record BulkOpened(int opened, int refused, List<Claim> claims) {
    }

    /** Open the chosen claims together, numbering them in list order (people in the order they were added,
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
                    | ActionNotAllowedException e) {
                // refused: left as it is
            }
        }
        return new BulkOpened(opened.size(), chosen.size() - opened.size(), opened);
    }

    /** What a bulk settle did: how many it settled (PENDING included) and refused, and the claims it changed. */
    public record BulkSettled(int settled, int refused, List<Claim> claims) {
    }

    /** Settle the chosen claims together in list order (people in the order they were added, each person's
     *  claims newest first), each as a single settle by the user would: one a clerk puts PENDING counts as
     *  settled, and one that cannot be settled is left as it is and counted as refused, as is one not on
     *  the sheet. */
    @Transactional
    public BulkSettled bulkSettle(String sheetId, String user, Collection<Long> claimIds) {
        Set<Long> chosen = new HashSet<>(claimIds);
        if (roles.isViewer(sheetId, user) || sheets.isArchived(sheetId)) {
            return new BulkSettled(0, chosen.size(), List.of());
        }
        List<Claim> settled = new ArrayList<>();
        for (Claim claim : inListOrder(sheetId, chosen)) {
            try {
                settled.add(changeStatus(sheetId, user, claim.getId(), ClaimStatus.SETTLED));
            } catch (ClaimStatusException | ClaimSettlerException | ClaimDeferException | ClaimProofException
                    | PeriodClosedException | ActionNotAllowedException
                    | ClaimNotFoundException e) {
                // refused: left as it is
            }
        }
        return new BulkSettled(settled.size(), chosen.size() - settled.size(), settled);
    }

    /** The sheet's chosen claims in list order: people in the order they were added, each person's claims
     *  newest first. */
    private List<Claim> inListOrder(String sheetId, Set<Long> chosen) {
        Map<Long, Integer> personOrder = new HashMap<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
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
     *  count, and bind each claim that counts to its rate figure now, under its late charge and floor (the charge is
     *  never bound: it keeps following the sheet's today, and the floor is applied on top; a PENDING claim is bound only once it is back to
     *  OPEN); a claim that does not count, is OPEN or PENDING and dated within one of its person's holds, or is
     *  deferred past the sheet's today, is left unbound. */
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
            if (!claim.getStatus().counts() || (claim.getStatus().isOpen() && person.heldOn(claim.getDate()))
                    || claim.deferredOn(today)) {
                claim.setStated(false, null);
                continue;
            }
            BigDecimal due = due(claim, person);
            if (due != null) {
                total = total.add(charged(claim, due));
                figure = true;
            }
            claim.setStated(true, due);
            counted++;
        }
        boolean rated = rates.findInForce(sheetId, person.gradeOn(today), today).isPresent();
        BigDecimal summed = total;
        BigDecimal dueTotal = rated || figure
                ? sheetRows.findById(sheetId).map(s -> s.capped(summed)).orElse(summed) : null;
        claims.saveAllAndFlush(theirs);
        statements.saveAndFlush(new Statement(sheetId, personId, today, dueTotal, counted));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + counted);
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

    /** Refuse an edit of a claim whose editor another user has open. */
    private void refuseLocked(Claim claim, String user) {
        if (claim.getLockedBy() != null && !claim.getLockedBy().equals(user)) {
            throw new ClaimLockedException(claim.getLockedBy());
        }
    }

    /** Refuse a change to a claim dated inside a closed period. Locks the sheet first, so a period being
     *  closed at the same moment is seen. */
    private void refuseClosed(String sheetId, LocalDate date) {
        sheetRows.findForUpdateById(sheetId).orElseThrow(SheetNotFoundException::new);
        if (periods.closedOn(sheetId, date)) {
            throw new PeriodClosedException();
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

    /** Refuse settling or approving a claim with no proof whose due is above the proof threshold in force on
     *  its date; a due of n/a, or no threshold then, needs none. */
    private void refuseUnproved(Claim claim, BigDecimal due) {
        BigDecimal threshold = sheetRows.findById(claim.getSheetId()).orElseThrow(SheetNotFoundException::new)
                .proofThresholdOn(claim.getDate());
        if (threshold != null && due != null && due.compareTo(threshold) > 0 && claim.getProofs() == 0) {
            throw new ClaimProofException("The claim's due is above the proof threshold: add a proof first.");
        }
    }

    /** Whether a clerk is settling a due above the sheet's clerk limit; a steward settles anything, and a
     *  due of n/a, or a sheet with no limit, is within it. */
    private boolean overLimit(String sheetId, String user, BigDecimal due) {
        BigDecimal limit = sheetRows.findById(sheetId).orElseThrow(SheetNotFoundException::new).getClerkLimit();
        return limit != null && due != null && due.compareTo(limit) > 0 && !roles.isSteward(sheetId, user);
    }

    /** Refuse a negative amount that does not name a SETTLED claim of the person by number, and a claim of
     *  0.00 or more that names one. */
    private void refuseCorrection(String sheetId, long personId, BigDecimal amount, String corrects) {
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
    }

    /** Refuse a claim dated after its person's retirement date. */
    private static void refuseRetired(Person person, LocalDate date) {
        if (person.retiredOn(date)) {
            throw new PersonRetiredException();
        }
    }

    /** A claim's figure: the due fixed when settled, else the one its last statement bound (not while
     *  PENDING), else its live due; none when that is n/a. */
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

    /** A claim's due as the screen shows it: fixed when settled, else its figure (bound or live) with its
     *  late charge on top; none when that is n/a. */
    private BigDecimal shownDue(Claim claim, Person person) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        BigDecimal figure = due(claim, person);
        return figure == null ? null : charged(claim, figure);
    }

    /** A claim's due from its figure: its late charge on the sheet's today on top while it is OPEN or
     *  PENDING, then at least the floor in force on its date (a correction has neither). */
    private BigDecimal charged(Claim claim, BigDecimal figure) {
        return Rollup.charged(sheetRows.findById(claim.getSheetId()), claim, figure);
    }

    /** The claim's rate figure: its amount at the rate in force on its date of its person's grade in force
     *  then, before any late charge or floor; none when no rate applies. */
    private BigDecimal liveDue(Claim claim, Person person) {
        Sheet sheet = sheetRows.findById(claim.getSheetId()).orElseThrow(SheetNotFoundException::new);
        RoundingMode rounding = sheet.rounding(roundingDate(claim));
        return rates.findInForce(claim.getSheetId(), person.gradeOn(claim.getDate()), claim.getDate())
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
