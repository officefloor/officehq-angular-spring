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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.history.HistoryService;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.PersonStatement;
import net.officefloor.hq.app.person.PersonStatementRepository;
import net.officefloor.hq.app.person.PersonWatch;
import net.officefloor.hq.app.person.PersonWatchRepository;
import net.officefloor.hq.app.person.StatementSummary;
import net.officefloor.hq.app.rate.Rate;
import net.officefloor.hq.app.rate.RateChain;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.HolidayAdded;
import net.officefloor.hq.app.settings.Permission;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.sheet.TodayChanged;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimService {

    /** The period name a claim in no period is numbered under. */
    private static final String NO_PERIOD = "X";

    private final ClaimRepository claims;
    private final ClaimLogRepository log;
    private final ClaimCommentRepository comments;
    private final ClaimLineRepository lines;
    private final SheetTagRepository tagsUsed;
    private final RetiredNumberRepository retired;
    private final PersonRepository people;
    private final PersonStatementRepository statements;
    private final PersonWatchRepository watches;
    private final RateChain rateChain;
    private final SheetService sheets;
    private final RollupService rollup;
    private final PeriodService periods;
    private final SettingsService settings;
    private final HistoryService history;
    private final Audit audit;

    /** A statement just taken: the person, their statement summary now, and their claims, newest first. */
    public record Statement(Person person, StatementSummary summary, List<Claim> claims) {
    }

    /** A split just made: the original, keeping the rest of its amount, and the new claim. */
    public record Split(Claim original, Claim split) {
    }

    public ClaimService(ClaimRepository claims, ClaimLogRepository log, ClaimCommentRepository comments, ClaimLineRepository lines, SheetTagRepository tagsUsed, RetiredNumberRepository retired, PersonRepository people, PersonStatementRepository statements,
            PersonWatchRepository watches, RateChain rateChain, SheetService sheets, RollupService rollup, PeriodService periods, SettingsService settings, HistoryService history, Audit audit) {
        this.claims = claims;
        this.log = log;
        this.comments = comments;
        this.lines = lines;
        this.tagsUsed = tagsUsed;
        this.retired = retired;
        this.people = people;
        this.statements = statements;
        this.watches = watches;
        this.rateChain = rateChain;
        this.sheets = sheets;
        this.rollup = rollup;
        this.periods = periods;
        this.settings = settings;
        this.history = history;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByListedDesc(sheetId);
    }

    /** Every tag ever used on the sheet, in order. */
    @Transactional(readOnly = true)
    public List<String> tagsUsed(String sheetId) {
        return tagsUsed.findBySheetIdOrderByTagAsc(sheetId).stream().map(SheetTag::getTag).toList();
    }

    /** The status logs of the sheet's claims, each oldest first, by claim id. */
    @Transactional(readOnly = true)
    public Map<Long, List<ClaimLog>> logs(String sheetId) {
        Map<Long, List<ClaimLog>> byClaim = new HashMap<>();
        for (ClaimLog line : log.findBySheetIdOrderByIdAsc(sheetId)) {
            byClaim.computeIfAbsent(line.getClaimId(), k -> new ArrayList<>()).add(line);
        }
        return byClaim;
    }

    /** A claim's status log, oldest first. */
    @Transactional(readOnly = true)
    public List<ClaimLog> logOf(long claimId) {
        return log.findByClaimIdOrderByIdAsc(claimId);
    }

    /** The comments on the sheet's claims, each oldest first, by claim id. */
    @Transactional(readOnly = true)
    public Map<Long, List<ClaimComment>> comments(String sheetId) {
        Map<Long, List<ClaimComment>> byClaim = new HashMap<>();
        for (ClaimComment c : comments.findBySheetIdOrderByIdAsc(sheetId)) {
            byClaim.computeIfAbsent(c.getClaimId(), k -> new ArrayList<>()).add(c);
        }
        return byClaim;
    }

    /** A claim's comments, oldest first. */
    @Transactional(readOnly = true)
    public List<ClaimComment> commentsOf(long claimId) {
        return comments.findByClaimIdOrderByIdAsc(claimId);
    }

    /** Comment on a claim in any status: the comment is kept with the user and the sheet's today,
     *  and COMMENT_ADDED is written. Refused (404) when missing and (422) when the text is blank. */
    @Transactional
    public Claim comment(String sheetId, String user, long id, String text) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A comment needs some text");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        String trimmed = text.trim();
        comments.save(new ClaimComment(sheetId, claim.getId(), user, sheets.today(sheetId), trimmed));
        record("COMMENT_ADDED", user, person, claim, claim.getNumber(), " text=" + trimmed);
        return claim;
    }

    /** The lines of the sheet's claims, each in the order added, by claim id. */
    @Transactional(readOnly = true)
    public Map<Long, List<ClaimLine>> lines(String sheetId) {
        Map<Long, List<ClaimLine>> byClaim = new HashMap<>();
        for (ClaimLine l : lines.findBySheetIdOrderByIdAsc(sheetId)) {
            byClaim.computeIfAbsent(l.getClaimId(), k -> new ArrayList<>()).add(l);
        }
        return byClaim;
    }

    /** A claim's lines, in the order added. */
    @Transactional(readOnly = true)
    public List<ClaimLine> linesOf(long claimId) {
        return lines.findByClaimIdOrderByIdAsc(claimId);
    }

    /** Add a line to a DRAFT claim: the claim's amount becomes its lines' quantities times their
     *  unit amounts, added up, it counts as edited by the user, and CLAIM_LINE_ADDED is written
     *  with the new amount. Refused (404) when missing, (422) when the label is blank, the
     *  quantity is below 1 or the unit amount is negative, (423) when due in a closed period,
     *  (409) unless a DRAFT, and (409) while another user holds its editor open. */
    @Transactional
    public Claim addLine(String sheetId, String user, long id, String label, int qty, BigDecimal unit) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (label == null || label.isBlank() || qty < 1 || unit.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A line needs a label, a quantity of at least 1 and a unit amount");
        }
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited");
        }
        refuseIfLocked(claim, user);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        ClaimLine line = lines.saveAndFlush(new ClaimLine(sheetId, claim.getId(), label.trim(), qty, unit.setScale(2)));
        claim.setAmount(linesTotal(claim.getId()));
        claim.setEditedBy(user);
        claims.flush();
        record("CLAIM_LINE_ADDED", user, person, claim, claim.getNumber(), " label=" + line.getLabel()
                + " qty=" + line.getQty() + " unit=" + line.getUnit().toPlainString());
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** The quantities times the unit amounts of a claim's lines, added up; null when it has none. */
    private BigDecimal linesTotal(long claimId) {
        return lines.findByClaimIdOrderByIdAsc(claimId).stream()
                .map(ClaimLine::amount)
                .reduce(BigDecimal::add)
                .map(total -> total.setScale(2))
                .orElse(null);
    }

    /** The claims of the ids on the sheet in list order: the people by their place in the list,
     *  each person's claims newest first; ids not on the sheet are left out. */
    @Transactional(readOnly = true)
    public List<Long> inListOrder(String sheetId, Collection<Long> ids) {
        Set<Long> wanted = new HashSet<>(ids);
        Map<Long, Integer> personOrder = new HashMap<>();
        List<Person> theirs = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        for (int i = 0; i < theirs.size(); i++) {
            personOrder.put(theirs.get(i).getId(), i);
        }
        return claims.findBySheetIdOrderByListedDesc(sheetId).stream()
                .filter(c -> wanted.contains(c.getId()))
                .sorted(Comparator.comparingInt(c -> personOrder.getOrDefault(c.getPersonId(), Integer.MAX_VALUE)))
                .map(Claim::getId)
                .toList();
    }

    /** Add a claim for a person on the sheet; a missing date takes the sheet's entry date while
     *  set, else its today. Refused
     *  (423) when the due-by it would be fixed with is in a closed period, (422) when it is after the person's
     *  retirement date, and (422) when it is a correction (a negative amount) that does not name
     *  the number of a SETTLED claim of the same person, and (409) when its reference (blank: none) is
     *  already used on the sheet, comparing without regard to case. Its tags are taken as
     *  {@link #tagsOf} has them. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tags) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = date != null ? date : sheets.entryDate(sheetId);
        int terms = termsOf(sheetId, person);
        LocalDate dueBy = dueByOf(sheetId, on, terms);
        refuseIfClosed(sheetId, dueBy);
        refuseIfRetired(person, on);
        String corrected = correctedBy(sheetId, personId, null, amount, corrects);
        String reference = refOf(sheetId, null, ref);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Claim fresh = new Claim(sheetId, personId, amount, on, dueBy, noteOf(note));
        fresh.setTerms(terms);
        fresh.setCorrects(corrected);
        fresh.setRef(reference);
        fresh.setTags(tagsOf(tags));
        // the sheet is locked (refuseIfClosed), so two claims added at once are listed one after the other
        fresh.setListed(claims.findLastListed(sheetId) + 1);
        fresh.setLateEntry(periods.softClosedOn(sheetId, dueBy));
        Claim claim = claims.saveAndFlush(fresh);
        keepTags(claim);
        logStatus(user, person, claim);
        record("CLAIM_ADDED", user, person, claim, claim.getNumber(),
                (claim.getRef() != null ? " ref=" + claim.getRef() : "")
                        + (claim.getTags() != null ? " tags=" + claim.getTags() : ""));
        if (claim.isLateEntry()) {
            record("LATE_ENTRY", user, person, claim, null);
        }
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Change a DRAFT claim's amount, date and note; a missing date takes the sheet's entry date
     *  while set, else its today.
     *  Refused (409) once the claim is no longer a DRAFT (an open claim is voided and a new one
     *  added instead), and (423) when its due-by (which the edit leaves) is in a closed period, and (422) when the new
     *  date is after the person's retirement date, or when it is a correction that does not name
     *  the number of a SETTLED claim of the same person, and (422) when it has lines and the amount is
     *  not their total, and (409) when its reference (blank: none) is
     *  used by another claim on the sheet, comparing without regard to case. Its tags are
     *  replaced as {@link #tagsOf} has them. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tags) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        LocalDate on = date != null ? date : sheets.entryDate(sheetId);
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited");
        }
        refuseIfLocked(claim, user);
        BigDecimal lined = linesTotal(claim.getId());
        if (lined != null && lined.compareTo(amount) != 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A claim with lines has their total as its amount");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        refuseIfRetired(person, on);
        String corrected = correctedBy(sheetId, person.getId(), claim.getId(), amount, corrects);
        String reference = refOf(sheetId, claim.getId(), ref);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setAmount(amount);
        claim.setCorrects(corrected);
        claim.setRef(reference);
        claim.setTags(tagsOf(tags));
        claim.setDate(on);
        claim.setNote(noteOf(note));
        claim.setLockedBy(null);
        claim.setEditedBy(user);
        claims.flush();
        keepTags(claim);
        record("CLAIM_EDITED", user, person, claim);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Open a DRAFT claim's editor for the user, locking the claim for everyone else until they
     *  save or cancel it or the sheet's today changes. Refused (404) when missing, (423) when
     *  dated in a closed period, (409) unless a DRAFT, and (409) while another user holds it. */
    @Transactional
    public Claim lock(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited");
        }
        refuseIfLocked(claim, user);
        claim.setLockedBy(user);
        claims.flush();
        return claim;
    }

    /** Close the user's editor of a claim without saving: their lock on it, if they hold it, is
     *  released. Refused (404) when missing. */
    @Transactional
    public Claim unlock(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (user.equals(claim.getLockedBy())) {
            claim.setLockedBy(null);
            claims.flush();
        }
        return claim;
    }

    /** A change of the sheet's today releases every claim's lock on it. */
    @EventListener
    @Transactional
    public void unlockAll(TodayChanged change) {
        claims.unlockAll(change.sheetId());
    }

    /** Move a claim to a new status; a move its status does not allow is refused (409), as is
     *  a move the user's role may not make by the sheet's permission matrix (403), settling by the user who opened the claim (403), settling a deferred claim (409), settling a PENDING claim (409: it is
     *  approved or rejected instead), voiding a SETTLED claim by anyone but a steward (403) or more
     *  than 7 days after its settlement (409), and any move of a claim due in a closed period (423). A
     *  clerk settling a claim whose due is above the sheet's clerk limit sends it to PENDING. Settling
     *  a claim whose due is above the proof threshold in force on its date while it has no proof is
     *  refused (422) before the clerk limit is considered. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus to) {
        return changeStatus(sheetId, user, id, to, null, false);
    }

    /** Settle the claims (in the order given) as {@link #changeStatus} does, all or nothing, as the
     *  sheet's next batch: each claim carries the batch's number, a claim sent to PENDING counts in
     *  it, and BATCH_SETTLED follows the claims' own records with the batch's count and the total of
     *  the frozen figures of those SETTLED. Any refusal refuses the whole batch, leaving every claim
     *  as it was and recording nothing. */
    @Transactional
    public void settleBatch(String sheetId, String user, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        int number = claims.findLastBatch(sheetId) + 1;
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (long id : ids) {
            Claim claim = changeStatus(sheetId, user, id, ClaimStatus.SETTLED, null, false);
            claim.setBatch(number);
            claims.save(claim);
            if (claim.getStatus() == ClaimStatus.SETTLED && claim.getSettledDue() != null) {
                total = total.add(claim.getSettledDue());
            }
        }
        claims.flush();
        audit.recordOnCommit("BATCH_SETTLED sheet=" + sheetId + " by=" + user + " batch=" + number
                + " count=" + ids.size() + " total=" + total.setScale(2).toPlainString());
    }

    /** Void a claim as {@link #changeStatus} does, with a reason from the sheet's list of void
     *  reasons (null: none): while the list is not empty, a void without one of them is refused
     *  (422), and a reason given while it is empty is refused too. The VOIDED record ends with it. */
    @Transactional
    public Claim voidClaim(String sheetId, String user, long id, String reason) {
        return changeStatus(sheetId, user, id, ClaimStatus.VOID, reason, false);
    }

    /** {@code judged}: the four-eyes rules and the proof rule were already judged on another claim
     *  (the one a part-settle split this one off), so they are not judged on this one. */
    private Claim changeStatus(String sheetId, String user, long id, ClaimStatus to, String voidReason, boolean judged) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDueBy());
        switch (to) {
            case OPEN -> settings.refuseUnlessAllowed(sheetId, user, Permission.OPEN);
            case SETTLED -> settings.refuseUnlessAllowed(sheetId, user, Permission.SETTLE);
            case VOID -> settings.refuseUnlessAllowed(sheetId, user, Permission.VOID);
            default -> {
            }
        }
        if (to == ClaimStatus.PENDING || to == ClaimStatus.REVIEW
                || (to == ClaimStatus.SETTLED && claim.getStatus() == ClaimStatus.PENDING)
                || (to == ClaimStatus.OPEN && claim.getStatus() == ClaimStatus.REVIEW)
                || !claim.getStatus().canMoveTo(to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + to);
        }
        if (to == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED) {
            refuseUnlessVoidableSettled(sheetId, user, claim);
        }
        if (to == ClaimStatus.VOID) {
            refuseUnlessListedReason(sheetId, voidReason);
        }
        if (to == ClaimStatus.SETTLED && claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim deferred until " + claim.getDeferredUntil() + " cannot be settled before then");
        }
        if (to == ClaimStatus.SETTLED && !judged) {
            refuseIfOpenerOrEditor(user, claim);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (to == ClaimStatus.OPEN) {
            refuseIfFlagged(sheetId, user, person);
            refuseIfNoteless(claim, shownDueOf(claim, person));
            refuseIfOverCap(sheetId, claim);
            if (needsReview(claim, person)) {
                // above the review threshold: a steward makes it OPEN
                to = ClaimStatus.REVIEW;
            }
        }
        // a claim bound by a statement settles at its bound figure, late charge included
        BigDecimal settling = to == ClaimStatus.SETTLED ? shownDueOf(claim, person) : null;
        if (to == ClaimStatus.SETTLED && !judged) {
            // before the clerk limit: a claim needing a proof never goes to PENDING
            refuseIfUnproven(claim, person, settling);
        }
        if (to == ClaimStatus.SETTLED && !settings.maySettle(sheetId, user, settling)) {
            // above the clerk limit: a steward approves or rejects it
            to = ClaimStatus.PENDING;
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        // a voided claim keeps its number (never reused), and the VOIDED record carries it
        String number = claim.getNumber();
        // a voided SETTLED claim keeps its frozen figure, which leaves every total
        boolean reversing = to == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED;
        BigDecimal reversed = claim.getSettledDue();
        claim.setStatus(to);
        if (to == ClaimStatus.OPEN) {
            number = nextNumber(sheetId, claim.getDueBy());
            claim.setNumber(number);
            claim.setOpenedBy(user);
        } else if (to == ClaimStatus.REVIEW) {
            claim.setOpenedBy(user);
            claim.setReviewOn(sheets.today(sheetId));
        } else if (to == ClaimStatus.SETTLED) {
            claim.setSettledDue(settling);
        } else if (to == ClaimStatus.VOID) {
            claim.setVoidReason(voidReason);
        }
        claims.flush();
        logStatus(user, person, claim);
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case REVIEW -> "CLAIM_REVIEW";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT, DELETED -> throw new IllegalArgumentException("A claim is deleted and restored on its own");
        };
        if (to == ClaimStatus.SETTLED) {
            record(event, user, person, claim, number, " due=" + dueText(claim.getSettledDue()));
            notifyWatchers(user, person, number);
        } else if (to == ClaimStatus.VOID) {
            record(event, user, person, claim, number, (reversing ? " reversed=" + dueText(reversed) : "")
                    + (voidReason != null ? " reason=" + voidReason : ""));
        } else {
            record(event, user, person, claim, number);
        }
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Delete a DRAFT claim: it becomes DELETED, counting nowhere, its log gains a DELETED line
     *  and CLAIM_DELETED is written. Refused (404) when missing, (423) when due in a closed
     *  period, (409) unless a DRAFT, and (409) while another user holds its editor open. */
    @Transactional
    public Claim delete(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (!claim.getStatus().canMoveTo(ClaimStatus.DELETED)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a DRAFT claim is deleted, not a " + claim.getStatus() + " one");
        }
        refuseIfLocked(claim, user);
        return moveDeleted(sheetId, user, claim, ClaimStatus.DELETED, "CLAIM_DELETED");
    }

    /** Restore a DELETED claim to DRAFT on the sheet's today it was deleted (its last DELETED log
     *  line): its log gains a DRAFT line and CLAIM_RESTORED is written. Refused (404) when missing,
     *  (423) when due in a closed period, (409) unless DELETED, and (409) on a later day. */
    @Transactional
    public Claim restore(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (!claim.getStatus().canMoveTo(ClaimStatus.DRAFT)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a DELETED claim is restored, not a " + claim.getStatus() + " one");
        }
        LocalDate deletedOn = log.findByClaimIdOrderByIdAsc(claim.getId()).stream()
                .filter(line -> line.getStatus() == ClaimStatus.DELETED)
                .map(ClaimLog::getOn)
                .reduce((first, second) -> second)
                .orElse(null);
        if (!sheets.today(sheetId).equals(deletedOn)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim is restored only on the day it was deleted");
        }
        return moveDeleted(sheetId, user, claim, ClaimStatus.DRAFT, "CLAIM_RESTORED");
    }

    private Claim moveDeleted(String sheetId, String user, Claim claim, ClaimStatus to, String event) {
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(to);
        claim.setLockedBy(null);
        claims.flush();
        logStatus(user, person, claim);
        record(event, user, person, claim);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Refuse a void (422) without a reason from the sheet's list of void reasons while it is not
     *  empty, and with a reason not on it. */
    private void refuseUnlessListedReason(String sheetId, String reason) {
        if (reason == null ? !settings.voidReasons(sheetId).isEmpty() : !settings.isVoidReason(sheetId, reason)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Choose a reason from the sheet's list to void the claim");
        }
    }

    /** Refuse opening a claim (403) of a person flagged on the sheet's today by anyone but a steward. */
    private void refuseIfFlagged(String sheetId, String user, Person person) {
        if (person.flagOn(sheets.today(sheetId)) != null && settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a steward opens a claim of a flagged person: " + person.getFlagReason());
        }
    }

    /** Refuse voiding a SETTLED claim (403) by anyone but a steward, and (409) once the sheet's
     *  today is more than {@link ClaimStatus#SETTLED_VOID_DAYS} days after its settlement (the
     *  first SETTLED line of its status log). */
    private void refuseUnlessVoidableSettled(String sheetId, String user, Claim claim) {
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward voids a SETTLED claim");
        }
        LocalDate settledOn = log.findByClaimIdOrderByIdAsc(claim.getId()).stream()
                .filter(line -> line.getStatus() == ClaimStatus.SETTLED)
                .map(ClaimLog::getOn)
                .findFirst()
                .orElse(null);
        LocalDate today = sheets.today(sheetId);
        if (settledOn == null || today.isAfter(settledOn.plusDays(ClaimStatus.SETTLED_VOID_DAYS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A SETTLED claim is voided only within " + ClaimStatus.SETTLED_VOID_DAYS + " days of its settlement");
        }
    }

    /** Defer an OPEN claim until a date after the sheet's today: until the sheet's today reaches
     *  it, the claim counts in no total, is not overdue, cannot be settled and is left unbound by a
     *  statement. Refused (404) when missing, (423) when due in a closed period, (409) unless
     *  OPEN, and (422) when the date is not after the sheet's today. */
    @Transactional
    public Claim defer(String sheetId, String user, long id, LocalDate until) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only an OPEN claim is deferred, not a " + claim.getStatus() + " one");
        }
        if (!until.isAfter(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A claim is deferred until a date after the sheet's today");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setDeferredUntil(until);
        claims.flush();
        record("CLAIM_DEFERRED", user, person, claim, claim.getNumber(), " until=" + until);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Remind an OPEN or PENDING claim: REMIND is written and the claim's reminded date becomes
     *  the sheet's today. Refused (404) when missing, (409) unless OPEN or PENDING, and (409) when
     *  it was already reminded on the sheet's today. When the sheet has an escalation gap and the
     *  previous reminder was at least that many days before today, the claim is escalated (once
     *  ever): ESCALATE is written after the REMIND. */
    @Transactional
    public Claim remind(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only an OPEN or PENDING claim is reminded, not a " + claim.getStatus() + " one");
        }
        LocalDate today = sheets.today(sheetId);
        if (today.equals(claim.getReminded())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim was already reminded on " + today);
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate previous = claim.getReminded();
        Integer escalateAfter = sheets.find(sheetId).map(Sheet::getEscalateAfter).orElse(null);
        boolean escalate = escalateAfter != null && previous != null && !claim.isEscalated()
                && !previous.plusDays(escalateAfter).isAfter(today);
        claim.setReminded(today);
        if (escalate) {
            claim.setEscalated(true);
        }
        claims.flush();
        record("REMIND", user, person, claim, claim.getNumber());
        if (escalate) {
            record("ESCALATE", user, person, claim, claim.getNumber());
        }
        return claim;
    }

    /** A steward waives an OPEN or PENDING claim's late charge: it bears none from then on, whatever
     *  its overdue state, and CLAIM_WAIVED is written. Refused (404) when missing, (403) for anyone
     *  but a steward, (409) unless OPEN or PENDING, and (409) when it is already waived. */
    @Transactional
    public Claim waive(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward waives a claim's late charge");
        }
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only an OPEN or PENDING claim is waived, not a " + claim.getStatus() + " one");
        }
        if (claim.isWaived()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim's late charge is already waived");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setWaived(true);
        claims.flush();
        record("CLAIM_WAIVED", user, person, claim, claim.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Hold an OPEN or PENDING claim, or release a held one: held, unless bound by a statement, it
     *  counts in no live total, as a held person's claims do. CLAIM_HELD or CLAIM_RELEASED is
     *  written. Refused (404) when missing and (409) unless OPEN or PENDING. */
    @Transactional
    public Claim toggleHold(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only an OPEN or PENDING claim is held or released, not a " + claim.getStatus() + " one");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setHeld(!claim.isHeld());
        claims.flush();
        record(claim.isHeld() ? "CLAIM_HELD" : "CLAIM_RELEASED", user, person, claim, claim.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** A steward re-dates an OPEN or PENDING claim: its due-by stays as it was fixed, a figure a
     *  statement bound it to stays bound, and so does its period (that of its due-by) and its
     *  number. Refused (404) when missing, (403) for anyone but a steward, (423) when its due-by
     *  is in a closed period, (409) unless OPEN or PENDING, and (422) when the new date is after
     *  the person's retirement date. */
    @Transactional
    public Claim redate(String sheetId, String user, long id, LocalDate date) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward re-dates a claim");
        }
        refuseIfClosed(sheetId, claim.getDueBy());
        if (!claim.getStatus().isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only an OPEN or PENDING claim is re-dated, not a " + claim.getStatus() + " one");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        refuseIfRetired(person, date);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate old = claim.getDate();
        claim.setDate(date);
        claims.flush();
        record("CLAIM_REDATED", user, person, claim, claim.getNumber(), " old=" + old + " new=" + date);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** A steward approves a PENDING claim: it becomes SETTLED, its due (the figure it shows, bound
     *  by a statement or live, with its late charge) frozen now. Refused (409) unless PENDING, (403) for a clerk or the user who
     *  opened or last edited it, (423) when it is due in a closed period, and (422) when its due is above the
     *  proof threshold in force on its date while it has no proof. */
    @Transactional
    public Claim approve(String sheetId, String user, long id) {
        Claim claim = pendingForSteward(sheetId, user, id);
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot approve it");
        }
        if (user.equals(claim.getEditedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who last edited a claim cannot approve it");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        BigDecimal due = shownDueOf(claim, person);
        refuseIfUnproven(claim, person, due);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.SETTLED);
        claim.setSettledDue(due);
        claims.flush();
        logStatus(user, person, claim);
        record("CLAIM_APPROVED", user, person, claim, claim.getNumber(), " due=" + dueText(due));
        notifyWatchers(user, person, claim.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Add a proof of a kind (null: none chosen, a receipt) to a claim, raising its proofs count by
     *  one. Refused (404) when missing, (422) when the kind is neither receipt nor photo, (423) when
     *  due in a closed period, and (409) when SETTLED or VOID. */
    @Transactional
    public Claim addProof(String sheetId, String user, long id, String kind) {
        ProofKind proofKind = kind == null ? ProofKind.RECEIPT : ProofKind.of(kind)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "A proof is a receipt or a photo"));
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                || claim.getStatus() == ClaimStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim takes no more proofs");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        claim.addProof(proofKind);
        claims.flush();
        record("CLAIM_PROOF_ADDED", user, person, claim, claim.getNumber(), " proofs=" + claim.getProofs()
                + (kind == null ? "" : " kind=" + proofKind.text()));
        return claim;
    }

    /** Refuse (403) settling a claim by the user who opened or last edited it. */
    private void refuseIfOpenerOrEditor(String user, Claim claim) {
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot settle it");
        }
        if (user.equals(claim.getEditedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who last edited a claim cannot settle it");
        }
    }

    /** Refuse (422) settling or approving a claim at a due (null: n/a) above the proof threshold in
     *  force on its date (its person's grade's then when one is set, else the sheet's) while it has
     *  no proof, or, while the sheet has proof kinds, without a proof of each of them. */
    private void refuseIfUnproven(Claim claim, Person person, BigDecimal due) {
        if (due == null) {
            return;
        }
        List<ProofKind> kinds = sheets.proofKinds(claim.getSheetId());
        boolean proven = claim.getProofs() > 0 && kinds.stream().allMatch(k -> claim.proofsOf(k) > 0);
        if (proven) {
            return;
        }
        BigDecimal threshold = sheets.proofThresholdFor(claim.getSheetId(), person.gradeOn(claim.getDate()), claim.getDate());
        if (threshold != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A claim whose due is above the proof threshold needs a proof (of each proof kind) first");
        }
    }

    /** Refuse (422) opening a claim at a due (null: n/a) above the note threshold in force on its
     *  date while its note is empty. */
    private void refuseIfNoteless(Claim claim, BigDecimal due) {
        if (due == null || (claim.getNote() != null && !claim.getNote().isBlank())) {
            return;
        }
        BigDecimal threshold = sheets.noteThresholdOn(claim.getSheetId(), claim.getDate());
        if (threshold != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A claim whose due is above the note threshold needs a note first");
        }
    }

    /** A steward re-settles a SETTLED claim: its due is frozen again at the figure it would show
     *  today as an open claim bound by no statement (today's rates, grades, floor and late
     *  charge). Refused (404) when missing, (423) when due in a closed period, (403) for anyone
     *  but a steward, and (409) unless SETTLED. */
    @Transactional
    public Claim resettle(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward re-settles a claim");
        }
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a SETTLED claim is re-settled, not a " + claim.getStatus() + " one");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        BigDecimal old = claim.getSettledDue();
        BigDecimal due = flooredOf(claim,
                LateCharge.of(sheets.find(sheetId)).addToOpen(claim, person, rateFigureOf(claim, person), roundingOf(claim)));
        claim.setSettledDue(due);
        claims.flush();
        logStatus(user, person, claim);
        record("CLAIM_RESETTLED", user, person, claim, claim.getNumber(),
                " old=" + dueText(old) + " new=" + dueText(due));
        notifyWatchers(user, person, claim.getNumber());
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** A steward rejects a PENDING claim with a reason: it goes back to OPEN, bound again by the
     *  person's last statement if that statement bound it. Refused (409) unless PENDING, (403) for
     *  a clerk, and (423) when it is due in a closed period. */
    @Transactional
    public Claim reject(String sheetId, String user, long id, String reason) {
        Claim claim = pendingForSteward(sheetId, user, id);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        claims.flush();
        logStatus(user, person, claim);
        record("CLAIM_REJECTED", user, person, claim, claim.getNumber(), " reason=" + reason);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** A steward (or a role the permission matrix lets reassign) moves a DRAFT, OPEN or PENDING claim to another person on the sheet: it keeps its
     *  date and takes the next number in its sequence (its old one never given again; a draft has none), is listed as the new person's newest claim, and loses any figure a
     *  statement bound it to (live again until the new person's next statement). Refused (404)
     *  when the claim or person is missing, (423) when it is due in a closed period, (403) for a
     *  role the sheet's permission matrix does not let reassign, (409) when SETTLED or VOID or already the person's, and (422) when it is dated after
     *  the new person's retirement date. */
    @Transactional
    public Claim reassign(String sheetId, String user, long id, long toPersonId) {
        return reassign(sheetId, user, id, toPersonId, false);
    }

    /** Reassign a claim as {@link #reassign(String, String, long, long)} does; when {@code toSame}
     *  a claim already the person's is moved too (listed as their newest, taking the next number in
     *  its sequence), as a bulk reassign does. */
    @Transactional
    public Claim reassign(String sheetId, String user, long id, long toPersonId, boolean toSame) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        Person to = people.findByIdAndSheetId(toPersonId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        refuseIfClosed(sheetId, claim.getDueBy());
        settings.refuseUnlessAllowed(sheetId, user, Permission.REASSIGN);
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID
                || claim.getStatus() == ClaimStatus.DELETED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reassigned");
        }
        if (!toSame && claim.getPersonId() == toPersonId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is already that person's");
        }
        refuseIfRetired(to, claim.getDate());
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        if (claim.getNumber() != null) {
            // moved (to another person, or as the person's newest): the next number in its sequence,
            // the old one never given again
            retired.save(new RetiredNumber(sheetId, claim.getNumber()));
            claims.flush();
            claim.setNumber(nextNumber(sheetId, claim.getDueBy()));
        }
        claim.setPersonId(to.getId());
        claim.unbind();
        claim.setListed(claims.findLastListed(sheetId) + 1);
        claims.flush();
        record("CLAIM_REASSIGNED", user, to, claim, claim.getNumber(),
                " from=" + from.getName() + " to=" + to.getName());
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Split an amount off an OPEN claim: the claim keeps the rest of its amount and loses any
     *  figure a statement bound it to, and a new OPEN claim of the amount, with the same date,
     *  note and tags, the next number, opened by the user, its due-by fixed now from the terms in force on
     *  the date (the person's own, else their grade's, else the sheet's), and naming the original's number as split-from, is listed as the person's newest (a late entry
     *  when a soft-closed period contains its due-by).
     *  Refused (404) when missing, (423) when due in a closed period, (403) for a role the
     *  sheet's permission matrix does not let open a claim, (409) unless OPEN and not deferred on
     *  the sheet's today, and (422) unless the amount is more than 0.00 and less than the claim's. */
    @Transactional
    public Split split(String sheetId, String user, long id, BigDecimal amount) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDueBy());
        settings.refuseUnlessAllowed(sheetId, user, Permission.OPEN);
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only an OPEN claim is split, not a " + claim.getStatus() + " one");
        }
        if (claim.isDeferredOn(sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim deferred until " + claim.getDeferredUntil() + " cannot be split before then");
        }
        if (amount.signum() <= 0 || amount.compareTo(claim.getAmount()) >= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The amount split off must be more than 0.00 and less than the claim's");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate on = claim.getDate();
        claim.setAmount(claim.getAmount().subtract(amount));
        claim.unbind();
        int terms = claim.getTerms();
        Claim fresh = new Claim(sheetId, person.getId(), amount, on, dueByOf(sheetId, on, terms), claim.getNote());
        fresh.setTerms(terms);
        fresh.setStatus(ClaimStatus.OPEN);
        fresh.setNumber(nextNumber(sheetId, fresh.getDueBy()));
        fresh.setOpenedBy(user);
        fresh.setSplitFrom(claim.getNumber());
        fresh.setTags(claim.getTags());
        fresh.setLateEntry(periods.softClosedOn(sheetId, fresh.getDueBy()));
        fresh.setListed(claims.findLastListed(sheetId) + 1);
        Claim split = claims.saveAndFlush(fresh);
        logStatus(user, person, split);
        record("CLAIM_SPLIT", user, person, claim, claim.getNumber(), " into=" + split.getNumber());
        record("CLAIM_OPENED", user, person, split);
        rollup.recordChanges(sheetId, user, before);
        return new Split(claim, split);
    }

    /** Part-settle an OPEN claim: the amount is split off as {@link #split} does, and the new claim
     *  is settled at once by the user as {@link #changeStatus} does (going to PENDING above the
     *  clerk limit). The four-eyes rules and the proof rule are judged on the original claim, as it
     *  was before the split; any refusal of the split or the settle refuses the whole, changing and
     *  recording nothing. */
    @Transactional
    public Split partSettle(String sheetId, String user, long id, BigDecimal amount) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfOpenerOrEditor(user, claim);
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        refuseIfUnproven(claim, person, shownDueOf(claim, person));
        Split s = split(sheetId, user, id, amount);
        Claim settled = changeStatus(sheetId, user, s.split().getId(), ClaimStatus.SETTLED, null, true);
        return new Split(s.original(), settled);
    }

    /** A steward reviews a REVIEW claim: it becomes OPEN, numbered now, its opener kept;
     *  CLAIM_REVIEWED then CLAIM_OPENED are written. Refused (404) when missing, (423) when due in a
     *  closed period, (409) unless REVIEW, and (403) when the user is not a steward. */
    @Transactional
    public Claim review(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a REVIEW claim is reviewed, not a " + claim.getStatus() + " one");
        }
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward reviews a claim");
        }
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        record("CLAIM_REVIEWED", user, person, claim, null);
        claim.setStatus(ClaimStatus.OPEN);
        claim.setNumber(nextNumber(sheetId, claim.getDueBy()));
        claims.flush();
        logStatus(user, person, claim);
        record("CLAIM_OPENED", user, person, claim);
        rollup.recordChanges(sheetId, user, before);
        return claim;
    }

    /** Whether opening the claim sends it to REVIEW: its due (null: n/a never does) is above the
     *  review threshold in force on its date. */
    private boolean needsReview(Claim claim, Person person) {
        BigDecimal threshold = sheets.reviewThresholdOn(claim.getSheetId(), claim.getDate());
        BigDecimal due = shownDueOf(claim, person);
        return threshold != null && due != null && due.compareTo(threshold) > 0;
    }

    /** The PENDING claim a steward decides on: refused (404) when missing, (423) when dated in a
     *  closed period, (409) unless PENDING, and (403) when the user is not a steward. */
    private Claim pendingForSteward(String sheetId, String user, long id) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        refuseIfClosed(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only a PENDING claim is approved or rejected, not a " + claim.getStatus() + " one");
        }
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a steward approves or rejects a PENDING claim");
        }
        return claim;
    }

    /** Write the claim's status now to its log: by the user, on the sheet's today, with the due
     *  it shows at this moment. */
    private void logStatus(String user, Person person, Claim claim) {
        BigDecimal due = shownDueOf(claim, person);
        log.save(new ClaimLog(claim.getSheetId(), claim.getId(), claim.getStatus(), user,
                sheets.today(claim.getSheetId()), due == null ? null : due.setScale(2, RoundingMode.HALF_EVEN)));
    }

    private static String dueText(BigDecimal due) {
        return due == null ? "n/a" : due.toPlainString();
    }

    /** Take a statement of a person: record their due-total now, and bind each of their claims
     *  that counts (OPEN, PENDING or SETTLED, but no unbound open one within a hold, nor a deferred one) to its figure now; the others
     *  are left unbound. Taken as of a date ({@code asOf}, not null), the sheet as it stood at the end
     *  of that date stands in for it now (see {@link #statementAsOf}). */
    @Transactional
    public Statement statement(String sheetId, String user, long personId, LocalDate asOf) {
        if (asOf != null) {
            return statementAsOf(sheetId, user, personId, asOf);
        }
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate today = sheets.today(sheetId);
        List<Claim> theirs = claims.findByPersonForUpdate(personId, sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        List<Claim> counted = theirs.stream().filter(c -> counts(c, person, today)).toList();
        BigDecimal dueTotal = Sheet.capped(dueTotalOf(counted, person, today), sheets.cap(sheetId));
        for (Claim c : theirs) {
            if (counts(c, person, today)) {
                // the statement binds the due as shown, late charge included (a bound one stays as it is)
                c.bind(shownDueOf(c, person));
            } else {
                c.unbind();
            }
        }
        claims.flush();
        return stated(sheetId, user, person, theirs, dueTotal, counted.size(), today, null, before);
    }

    /** Take a statement of a person as of a date on or before the sheet's today: their claims that
     *  counted at the end of it are bound to their figures as they stood then (every change made
     *  while the sheet's today was on or before it counts, every later one does not), with that date
     *  standing in for the sheet's today in every figure; their other claims are left unbound.
     *  Refused (422) when the date is after the sheet's today, or the person was not yet added then. */
    private Statement statementAsOf(String sheetId, String user, long personId, LocalDate asOf) {
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate today = sheets.today(sheetId);
        if (asOf.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A statement is taken as of a date on or before the sheet's today");
        }
        HistoryService.AsOf then = history.asOf(sheetId, asOf);
        Person was = then.people().get(personId);
        if (was == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The person was not yet on the sheet on " + asOf);
        }
        List<Claim> theirs = claims.findByPersonForUpdate(personId, sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        RollupService.Stated stated = RollupService.statedOn(then.sheet(), was, personId,
                List.copyOf(then.claims().values()), new RateChain.Levels(sheetId, then.rates(),
                        rateChain.ancestors(sheetId).stream().map(a -> history.asOf(a, asOf).rates()).toList()));
        Map<Long, BigDecimal> bound = new HashMap<>();
        then.claims().forEach((id, c) -> {
            if (stated.counted().containsKey(c)) {
                bound.put(id, stated.counted().get(c));
            }
        });
        for (Claim c : theirs) {
            if (bound.containsKey(c.getId())) {
                c.bind(bound.get(c.getId()));
            } else {
                c.unbind();
            }
        }
        claims.flush();
        return stated(sheetId, user, person, theirs, stated.dueTotal(), stated.counted().size(), today, asOf, before);
    }

    /** Record a statement just taken (as of {@code asOf}, or null) and audit it. */
    private Statement stated(String sheetId, String user, Person person, List<Claim> theirs, BigDecimal dueTotal,
            int counted, LocalDate today, LocalDate asOf, RollupService.Snapshot before) {
        statements.saveAndFlush(new PersonStatement(sheetId, person.getId(), dueTotal, counted, today, asOf, user));
        audit.recordOnCommit("STATEMENT sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (dueTotal == null ? "n/a" : dueTotal.toPlainString()) + " claims=" + counted
                + (asOf == null ? "" : " asof=" + asOf));
        rollup.recordChanges(sheetId, user, before);
        return new Statement(person, StatementSummary.of(statements.findByPersonIdOrderByIdAsc(person.getId())), theirs);
    }

    /** A steward cancels a person's last statement: it is removed, and every claim it bound is
     *  live again (a figure an earlier statement bound is not restored). Refused (404) when the
     *  person is missing, (403) for anyone but a steward, and (409) when the person has no
     *  statement. */
    @Transactional
    public Statement cancelStatement(String sheetId, String user, long personId) {
        Person person = people.findForUpdate(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (settings.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward cancels a statement");
        }
        List<PersonStatement> taken = statements.findByPersonIdOrderByIdAsc(personId);
        if (taken.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The person has no statement to cancel");
        }
        PersonStatement last = taken.get(taken.size() - 1);
        List<Claim> theirs = claims.findByPersonForUpdate(personId, sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        theirs.forEach(Claim::unbind);
        claims.flush();
        statements.delete(last);
        statements.flush();
        audit.recordOnCommit("STATEMENT_CANCELLED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " due-total=" + (last.getDueTotal() == null ? "n/a" : last.getDueTotal().toPlainString()));
        rollup.recordChanges(sheetId, user, before);
        return new Statement(person, StatementSummary.of(statements.findByPersonIdOrderByIdAsc(personId)), theirs);
    }

    /** The terms a claim of the person is fixed with as it is created, whatever its date: the
     *  person's own terms in force on the sheet's today, else those of the grade they have then in
     *  force then, else the sheet's then. */
    private int termsOf(String sheetId, Person person) {
        LocalDate on = sheets.today(sheetId);
        Integer own = person.termsOn(on);
        return own != null ? own
                : sheets.find(sheetId).map(s -> s.termsFor(person.gradeOn(on), on)).orElse(Sheet.DEFAULT_TERMS);
    }

    /** The due-by of a claim dated {@code on} with the terms: the terms-th business day after its
     *  date. Business days skip every Saturday, Sunday and holiday on the sheet. */
    private LocalDate dueByOf(String sheetId, LocalDate on, int terms) {
        Set<LocalDate> holidays = new HashSet<>(settings.holidays(sheetId));
        return BusinessDays.after(on, terms, holidays);
    }

    /** A holiday just added re-fixes the due-by of every claim on the sheet that is neither SETTLED
     *  nor VOID and is due on or after it: from its date, with the terms it was fixed with, over the
     *  holidays on the sheet now. The figures that moves are recorded. */
    @EventListener
    @Transactional
    public void redate(HolidayAdded added) {
        String sheetId = added.sheetId();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Set<LocalDate> holidays = new HashSet<>(settings.holidays(sheetId));
        for (Claim c : claims.findBySheetIdForUpdate(sheetId)) {
            if (c.getStatus() == ClaimStatus.SETTLED || c.getStatus() == ClaimStatus.VOID
                    || c.getStatus() == ClaimStatus.DELETED || c.getDueBy().isBefore(added.day())) {
                continue;
            }
            c.setDueBy(BusinessDays.after(c.getDate(), c.getTerms(), holidays));
        }
        claims.flush();
        rollup.recordChanges(sheetId, added.user(), before);
    }

    /** The number a claim due by {@code date} takes as it is opened: under period numbering (set,
     *  and the sheet's today on or after it) the next within the period containing the due-by
     *  (<period name>-<n>), or X-<n> when none does; otherwise the sheet's next number within the
     *  fiscal year of the due-by (FY<yyyy>-<n>). */
    public String nextNumber(String sheetId, LocalDate date) {
        if (!sheets.periodNumberingOn(sheetId)) {
            return sheets.nextFiscalClaimNumber(sheetId, date);
        }
        // held until the transaction ends, so two claims opened at once are numbered one after the other
        sheets.lock(sheetId);
        String prefix = periods.containing(sheetId, date).map(Period::getName).orElse(NO_PERIOD) + "-";
        List<String> given = new ArrayList<>(claims.findNumbers(sheetId));
        given.addAll(retired.findNumbers(sheetId));
        int last = given.stream()
                .filter(n -> n.startsWith(prefix))
                .map(n -> n.substring(prefix.length()))
                .filter(n -> n.matches("\\d{1,9}"))
                .mapToInt(Integer::parseInt)
                .max().orElse(0);
        return prefix + (last + 1);
    }

    /** Refuse (409) opening a claim whose due-by is in a period with a cap when its person already
     *  has that many OPEN or PENDING claims due in the period. */
    private void refuseIfOverCap(String sheetId, Claim claim) {
        Optional<Period> period = periods.containing(sheetId, claim.getDueBy());
        if (period.isEmpty() || period.get().getCap() == null) {
            return;
        }
        Period p = period.get();
        long open = claims.findByPersonForUpdate(claim.getPersonId(), sheetId).stream()
                .filter(c -> !c.getId().equals(claim.getId()))
                .filter(c -> c.getStatus() == ClaimStatus.OPEN || c.getStatus() == ClaimStatus.PENDING)
                .filter(c -> c.getDueBy() != null && p.contains(c.getDueBy()))
                .count();
        if (open >= p.getCap()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The person already has " + p.getCap() + " open claims in period " + p.getName());
        }
    }

    /** Refuse (423) a change to a claim due by a date in a closed period, holding the sheet locked until
     *  the transaction ends so a period cannot be closed meanwhile. */
    private void refuseIfClosed(String sheetId, LocalDate date) {
        sheets.lock(sheetId);
        periods.refuseIfClosed(sheetId, date);
    }

    /** Refuse (409) a change to a claim whose editor another user holds open. */
    private static void refuseIfLocked(Claim claim, String user) {
        if (claim.isLockedFor(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is being edited by " + claim.getLockedBy());
        }
    }

    /** The number a claim of the amount corrects: none unless the amount is negative, when it must
     *  be the number of a SETTLED claim of the same person, or the claim is refused (422); refused
     *  (422) too when it exceeds what remains of that claim: its amount less the corrections already
     *  made against it (VOID and DELETED ones, and the claim of the id being edited, left out). */
    private String correctedBy(String sheetId, long personId, Long id, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            return null;
        }
        if (corrects == null
                || !claims.existsBySheetIdAndPersonIdAndNumberAndStatus(sheetId, personId, corrects, ClaimStatus.SETTLED)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A negative amount must correct a settled claim of the same person");
        }
        BigDecimal target = claims.findFirstBySheetIdAndPersonIdAndNumber(sheetId, personId, corrects)
                .map(Claim::getAmount).orElse(BigDecimal.ZERO);
        BigDecimal remaining = target.add(claims.sumCorrections(sheetId, personId, corrects, id));
        if (amount.negate().compareTo(remaining) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "A correction may not exceed the " + remaining + " that remains of " + corrects);
        }
        return corrects;
    }

    /** A claim's reference with surrounding spaces removed: none when blank; refused (409) when
     *  another claim on the sheet than the one of the id (null: a new claim) already carries it,
     *  comparing without regard to case. */
    private String refOf(String sheetId, Long id, String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String trimmed = ref.trim();
        if (claims.existsRef(sheetId, trimmed, id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The reference " + trimmed + " is already used on the sheet");
        }
        return trimmed;
    }

    /** A claim's tags as the form gives them, comma separated: each trimmed and lower-cased, blank
     *  ones and repeats dropped, sorted and joined by commas; none when no tag is left. A person's
     *  tags are taken the same way. */
    public static String tagsOf(String tags) {
        if (tags == null) {
            return null;
        }
        Set<String> each = new TreeSet<>();
        for (String tag : tags.split(",")) {
            String t = tag.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                each.add(t);
            }
        }
        return each.isEmpty() ? null : String.join(",", each);
    }

    /** Keep each of the claim's tags among those ever used on its sheet. */
    public void keepTags(Claim claim) {
        if (claim.getTags() == null) {
            return;
        }
        for (String tag : claim.getTags().split(",")) {
            if (!tagsUsed.existsBySheetIdAndTag(claim.getSheetId(), tag)) {
                tagsUsed.save(new SheetTag(claim.getSheetId(), tag));
            }
        }
    }

    /** Refuse (422) a claim dated after its person's retirement date. */
    private static void refuseIfRetired(Person person, LocalDate date) {
        if (person.retiredBefore(date)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "The date is after the person's retirement date");
        }
    }

    /** Whether a claim counts toward claimed and due totals: only OPEN (or PENDING) and SETTLED ones
     *  do, but not a held open one, nor an open one dated within a hold of its person, nor one of a
     *  person whose retirement date the sheet's today has reached, unless bound by a statement, nor one deferred
     *  on the sheet's today. */
    private static boolean counts(Claim c, Person person, LocalDate today) {
        return c.getStatus() == ClaimStatus.SETTLED
                || (c.getStatus().isOpen() && !c.isDeferredOn(today)
                        && (c.isBound() || !(c.isHeld() || person.heldOn(c.getDate()) || person.retiredOn(today))));
    }

    /** The due a claim shows: the due fixed when it was settled, else the one a statement bound it to
     *  (null: n/a), late charge included, else its rate figure with its late charge on top, raised to
     *  the floor in force on its date. */
    private BigDecimal shownDueOf(Claim claim, Person person) {
        if (claim.getSettledDue() != null) {
            return claim.getSettledDue();
        }
        if (claim.isBound()) {
            return claim.getStatedDue();
        }
        return flooredOf(claim,
                LateCharge.of(sheets.find(claim.getSheetId())).addTo(claim, person, rateFigureOf(claim, person), roundingOf(claim)));
    }

    /** A due (null: n/a, which stays n/a) raised to the sheet's floor in force on the claim's date; a
     *  correction's never is. */
    private BigDecimal flooredOf(Claim claim, BigDecimal due) {
        if (due == null || claim.isCorrection()) {
            return due;
        }
        return due.max(sheets.floorOn(claim.getSheetId(), claim.getDate()));
    }

    /** A person's due-total: the sum of the dues their counting claims show (n/a ones skipped);
     *  null (n/a) when their grade in force on the sheet's today has no rate then, unless a claim
     *  shows a due (a fixed or bound one, or one at the rate of the grade they had on its date). */
    private BigDecimal dueTotalOf(List<Claim> counted, Person person, LocalDate today) {
        List<BigDecimal> dues = counted.stream().map(c -> shownDueOf(c, person)).filter(d -> d != null).toList();
        if (rateChain.findInForce(person.getSheetId(), person.gradeOn(today), today).isEmpty() && dues.isEmpty()) {
            return null;
        }
        return dues.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** The claim's amount times the rate, in force on the claim's date, of its person's grade then (as
     *  the claim applies the rates: not a forward-only one saved after it was added), divided by
     *  100, rounded as its rounding policy has it; null with no rate in force then. */
    private BigDecimal rateFigureOf(Claim claim, Person person) {
        return rateChain.findInForce(claim.getSheetId(), person.gradeOn(claim.getDate()), claim.getDate(), claim.getAdded())
                .map(Rate::getPct)
                .map(pct -> claim.getAmount().multiply(pct).divide(BigDecimal.valueOf(100), 2, roundingOf(claim)))
                .orElse(null);
    }

    /** How the claim's live figures round: by the sheet's policy date against its date, or for a
     *  correction the date of the claim it corrects. */
    private RoundingMode roundingOf(Claim claim) {
        LocalDate on = claim.getCorrects() == null ? claim.getDate()
                : claims.findFirstBySheetIdAndPersonIdAndNumber(claim.getSheetId(), claim.getPersonId(), claim.getCorrects())
                        .map(Claim::getDate).orElse(claim.getDate());
        return Rounding.of(sheets.find(claim.getSheetId())).on(on);
    }

    private void record(String event, String user, Person person, Claim claim) {
        record(event, user, person, claim, claim.getNumber());
    }

    /** Amounts are written as the screen shows them (two decimals), dates as yyyy-mm-dd. */
    private void record(String event, String user, Person person, Claim claim, String number) {
        record(event, user, person, claim, number, "");
    }

    private void record(String event, String user, Person person, Claim claim, String number, String extra) {
        audit.recordOnCommit(event + " sheet=" + claim.getSheetId() + " by=" + user
                + " person=" + person.getName() + " amount=" + claim.getAmount().setScale(2).toPlainString()
                + " date=" + claim.getDate() + (number != null ? " number=" + number : "") + extra);
    }

    /** Notify each user watching the person that one of their claims was settled, in the order
     *  they started watching. */
    private void notifyWatchers(String user, Person person, String number) {
        for (PersonWatch w : watches.findByPersonIdOrderByIdAsc(person.getId())) {
            audit.recordOnCommit("NOTIFY sheet=" + person.getSheetId() + " by=" + user + " user=" + w.getUserName()
                    + " person=" + person.getName() + " number=" + number);
        }
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
