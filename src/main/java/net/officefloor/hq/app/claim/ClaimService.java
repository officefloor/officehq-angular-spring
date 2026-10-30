package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.access.Permission;
import net.officefloor.hq.app.access.Role;
import net.officefloor.hq.app.holiday.HolidayService;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.person.WatchService;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.TodayChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The claims on a sheet: listed per person newest first, added, edited and moved through their
 * statuses one at a time. Only a DRAFT can be edited. A voided claim keeps
 * its number, which is never reused; a claim opened while period numbering is on is numbered within its
 * period instead of sheet-wide. Settling a claim fixes its due at the rate its person's grade has then on the claim's date
 * (at least the sheet's floor then), or at the
 * figure a statement bound it to, plus the late charge it has then. A claim belongs to the period containing its due-by
 * (fixed when it was added, moved past the sheet's holidays then); no claim due by a date inside a closed period can be added, edited or moved,
 * and no claim dated after its person's retirement date can be added or edited to that date. A clerk
 * settling a claim whose due is above the sheet's clerk limit leaves it PENDING instead; a steward settles
 * anything, and approves a PENDING claim (settling it, its due fixed then) or rejects it with a reason
 * (back to OPEN); the user who opened a claim cannot approve it. A steward re-settles a SETTLED claim,
 * fixing its due again at the figure it would have today as an OPEN claim bound by no statement. A claim
 * with a negative amount is a correction: it is added or edited only when it names, by number, a SETTLED
 * claim of the same person, and only a correction names one. A claim whose due is above the sheet's proof
 * threshold in force on its date cannot be settled or approved while it has no proof.
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetRows;
    private final RateService rates;
    private final Rollup rollup;
    private final PeriodService periods;
    private final AccessService access;
    private final WatchService watches;
    private final HolidayService holidays;
    private final Tags tags;
    private final ApplicationEventPublisher events;

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets,
            SheetRepository sheetRows, RateService rates, Rollup rollup, PeriodService periods, AccessService access,
            WatchService watches, HolidayService holidays, Tags tags, ApplicationEventPublisher events) {
        this.tags = tags;
        this.holidays = holidays;
        this.access = access;
        this.watches = watches;
        this.rollup = rollup;
        this.periods = periods;
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
        this.sheetRows = sheetRows;
        this.rates = rates;
        this.events = events;
    }

    /** Every claim on the sheet by the person who made it, each person's newest first. */
    @Transactional(readOnly = true)
    public Map<Long, List<Claim>> bySheet(String sheetId) {
        return claims.findBySheetIdOrderByListedDesc(sheetId).stream()
                .collect(Collectors.groupingBy(Claim::getPersonId));
    }

    @Transactional(readOnly = true)
    public List<Claim> forPerson(String sheetId, long personId) {
        return claims.findBySheetIdAndPersonIdOrderByListedDesc(sheetId, personId);
    }

    /** Add a claim; a date left out takes the sheet's entry date while set, else its today. Returns the person it belongs to. */
    @Transactional
    public long add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        return add(sheetId, user, personId, amount, date, note, null);
    }

    /**
     * Add a claim, a correction naming the number of the claim it corrects; a date left out takes the
     * sheet's entry date while set, else its today. Returns the person it belongs to.
     */
    @Transactional
    public long add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        return add(sheetId, user, personId, amount, date, note, corrects, null);
    }

    /**
     * Add a claim as {@link #add(String, String, long, BigDecimal, LocalDate, String, String)} does, carrying an
     * external reference (surrounding spaces removed; blank: none), refused when a claim on the sheet already
     * carries it, compared without regard to case. Returns the person it belongs to.
     */
    @Transactional
    public long add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref) {
        return add(sheetId, user, personId, amount, date, note, corrects, ref, null);
    }

    /**
     * Add a claim as {@link #add(String, String, long, BigDecimal, LocalDate, String, String, String)} does,
     * carrying the tags in the comma separated text (see {@link Tags#normalize}; null: none). Returns the
     * person it belongs to.
     */
    @Transactional
    public long add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tagText) {
        String tagged = Tags.normalize(tagText);
        String reference = requireFreeRef(sheetId, ref, null);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = date != null ? date : sheets.claimDate(sheetId);
        sheets.load(sheetId, user);
        LocalDate dueBy = holidays.dueBy(sheetId, on,
                sheets.settings(sheetId).termsFor(person.getTerms(), person.gradeOn(on), on));
        periods.requireOpen(sheetId, dueBy);
        requireNotRetired(person, on);
        LocalDate correctsDate = requireCorrects(sheetId, personId, amount, corrects);
        Rollup.Figures before = rollup.figures(sheetId);
        Claim claim = new Claim(sheetId, personId, amount, on, note == null ? "" : note, corrects);
        claim.setCorrectsDate(correctsDate);
        claim.setLateEntry(periods.softClosedOn(sheetId, dueBy).isPresent());
        claim.setDueBy(dueBy);
        claim.setRef(reference);
        claim.setTags(tagged);
        log(sheetId, user, claim, person);
        claims.saveAndFlush(claim);
        tags.use(sheetId, tagged);
        events.publishEvent(new ClaimChanged("CLAIM_ADDED", sheetId, user, person.getName(), amount, on, null, null,
                null, null, reference, tagged.isEmpty() ? null : tagged));
        if (claim.isLateEntry()) {
            events.publishEvent(new ClaimChanged("LATE_ENTRY", sheetId, user, person.getName(), amount, on, null, null));
        }
        rollup.changed(sheetId, user, before);
        return personId;
    }

    /**
     * Edit a claim's amount, date and note; only a DRAFT can be edited (an open claim is voided and
     * added again instead), and an edit is refused when a closed period contains its due-by (which an edit
     * leaves as it was fixed), or
     * when the date it is left with is after its person's retirement date, or when its external reference
     * (surrounding spaces removed; blank: none) is carried by another claim on the sheet, compared without
     * regard to case. Its tags are those in the comma separated text (see {@link Tags#normalize}; null: none).
     * Returns the person it belongs to.
     */
    @Transactional
    public long edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects, String ref, String tagText) {
        String tagged = Tags.normalize(tagText);
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        String reference = requireFreeRef(sheetId, ref, claimId);
        LocalDate on = date != null ? date : sheets.today(sheetId);
        periods.requireOpen(sheetId, claim.getDueBy());
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited; void it and add a new one");
        }
        requireNotLockedByOther(claim, user);
        people.findByIdAndSheetId(claim.getPersonId(), sheetId).ifPresent(p -> requireNotRetired(p, on));
        LocalDate correctsDate = requireCorrects(sheetId, claim.getPersonId(), amount, corrects);
        Rollup.Figures before = rollup.figures(sheetId);
        claim.setAmount(amount);
        claim.setCorrects(corrects);
        claim.setCorrectsDate(correctsDate);
        claim.setDate(on);
        claim.setNote(note == null ? "" : note);
        claim.setRef(reference);
        claim.setTags(tagged);
        claim.setLockedBy(null);
        claims.flush();
        tags.use(sheetId, tagged);
        String person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).map(Person::getName).orElse("");
        events.publishEvent(new ClaimChanged("CLAIM_EDITED", sheetId, user, person, claim.getAmount(),
                claim.getDate(), claim.getShownNumber(), null));
        rollup.changed(sheetId, user, before);
        return claim.getPersonId();
    }

    /**
     * Change a claim's amount alone, keeping its date, note and the claim it corrects; refused exactly as
     * an {@link #edit} with that amount is. Returns the person it belongs to.
     */
    @Transactional
    public long editAmount(String sheetId, String user, long claimId, BigDecimal amount) {
        Claim claim = claim(sheetId, claimId);
        return edit(sheetId, user, claimId, amount, claim.getDate(), claim.getNote(), claim.getCorrects(),
                claim.getRef(), claim.getTags());
    }

    /**
     * An external reference with its surrounding spaces removed, or null when blank; refused when a claim on
     * the sheet other than {@code claimId} (null: any claim) carries it, compared without regard to case.
     */
    String requireFreeRef(String sheetId, String ref, Long claimId) {
        String reference = ref == null || ref.isBlank() ? null : ref.strip();
        if (reference != null && (claimId == null ? claims.existsBySheetIdAndRefIgnoreCase(sheetId, reference)
                : claims.existsBySheetIdAndRefIgnoreCaseAndIdNot(sheetId, reference, claimId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The reference \"" + reference + "\" is already used on the sheet");
        }
        return reference;
    }

    /**
     * Open a claim's editor, locking the claim for every other user until the editor is saved or cancelled
     * or the sheet's today changes. Only a DRAFT due by a date outside every closed period can be edited, and a claim
     * another user's editor holds is refused; a refusal takes no lock. No audit line. Returns the person
     * it belongs to.
     */
    @Transactional
    public long lock(String sheetId, String user, long claimId) {
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited; void it and add a new one");
        }
        requireNotLockedByOther(claim, user);
        claim.setLockedBy(user);
        claims.flush();
        return claim.getPersonId();
    }

    /** Cancel a claim's editor, releasing the user's lock on it; another user's lock stays. Returns the person. */
    @Transactional
    public long unlock(String sheetId, String user, long claimId) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (user.equals(claim.getLockedBy())) {
            claim.setLockedBy(null);
            claims.flush();
        }
        return claim.getPersonId();
    }

    /** A change of the sheet's today releases every editor's lock on its claims. */
    @EventListener
    @Transactional
    public void on(TodayChanged e) {
        claims.findBySheetIdAndLockedByIsNotNull(e.sheetId()).forEach(c -> c.setLockedBy(null));
        claims.flush();
    }

    private static void requireNotLockedByOther(Claim claim, String user) {
        if (claim.getLockedBy() != null && !claim.getLockedBy().equals(user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is being edited by " + claim.getLockedBy());
        }
    }

    /**
     * Move a claim to a status; a move its status does not allow, opening, settling or voiding by a role the
     * sheet's permission matrix does not allow, settling by the user who opened
     * the claim, or moving a claim due by a date inside a closed period, is refused and changes nothing. Settling
     * by a clerk a claim whose due is above the clerk limit leaves it PENDING.
     * Returns the person it belongs to.
     */
    @Transactional
    public long move(String sheetId, String user, long claimId, ClaimStatus next) {
        Claim claim = claim(sheetId, claimId);
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next);
        }
        Permission permission = switch (next) {
            case OPEN -> Permission.OPEN;
            case SETTLED -> Permission.SETTLE;
            case VOID -> Permission.VOID;
            default -> null;
        };
        if (permission != null && !access.allows(sheetId, user, permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your role may not " + permission.action() + " a claim on this sheet");
        }
        String reversed = null;
        if (next == ClaimStatus.VOID && claim.getStatus() == ClaimStatus.SETTLED) {
            requireVoidableSettled(sheetId, user, claim);
            reversed = claim.getSettledDue() == null ? "n/a" : claim.getSettledDue().toPlainString();
        }
        if (next == ClaimStatus.SETTLED && Dues.deferred(claim, sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is deferred until " + claim.getDeferredUntil() + " and cannot be settled before then");
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot settle it");
        }
        if (next == ClaimStatus.OPEN) {
            requireNote(sheetId, claim);
        }
        Rollup.Figures before = rollup.figures(sheetId);
        if (next == ClaimStatus.OPEN) {
            giveNumber(sheetId, claim);
            claim.setOpenedBy(user);
        }
        String number = claim.getShownNumber();
        Person owner = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElse(null);
        String due = null;
        if (next == ClaimStatus.SETTLED) {
            BigDecimal fixed = dueNow(sheetId, claim, owner);
            requireProof(sheetId, claim, fixed);
            if (access.canSettle(sheetId, user, fixed)) {
                claim.setSettledDue(fixed);
                due = fixed == null ? "n/a" : fixed.toPlainString();
            } else {
                next = ClaimStatus.PENDING;
            }
        }
        claim.setStatus(next);
        log(sheetId, user, claim, owner);
        claims.flush();
        String person = owner == null ? "" : owner.getName();
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        events.publishEvent(new ClaimChanged(event, sheetId, user, person, claim.getAmount(), claim.getDate(), number,
                due, null, reversed));
        if (next == ClaimStatus.SETTLED) {
            watches.notifyWatchers(sheetId, user, owner, number);
        }
        rollup.changed(sheetId, user, before);
        return claim.getPersonId();
    }

    /**
     * Defer an OPEN claim until a date after the sheet's today: until the sheet's today reaches it the claim
     * counts in no total, is not overdue, cannot be settled and no statement binds it (see
     * {@link Dues#deferred}). A claim not OPEN, a date not after today, or a claim due by a date inside a closed
     * period is refused and nothing changes. Returns the person it belongs to.
     */
    @Transactional
    public long defer(String sheetId, String user, long claimId, LocalDate until) {
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be deferred; only an OPEN one can");
        }
        LocalDate today = sheets.today(sheetId);
        if (!until.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim can only be deferred to a date after the sheet's today (" + today + ")");
        }
        Rollup.Figures before = rollup.figures(sheetId);
        claim.setDeferredUntil(until);
        claims.flush();
        String person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).map(Person::getName).orElse("");
        events.publishEvent(new ClaimDeferred(sheetId, user, person, claim.getAmount(), claim.getDate(),
                claim.getShownNumber(), until));
        rollup.changed(sheetId, user, before);
        return claim.getPersonId();
    }

    /**
     * A steward re-dates an OPEN or PENDING claim. Its due-by stays as it was fixed and a figure a statement
     * bound it to stays bound; so does its period (the one containing its due-by) and its number. A claim
     * not OPEN or PENDING, one due by a date inside a closed period, a new date after its person's
     * retirement date, or a re-date by
     * anyone but a steward is refused and nothing changes. Returns the person it belongs to.
     */
    @Transactional
    public long redate(String sheetId, String user, long claimId, LocalDate date) {
        Claim claim = claim(sheetId, claimId);
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a steward can re-date a claim");
        }
        if (claim.getStatus() != ClaimStatus.OPEN && claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-dated; only an OPEN or PENDING one can");
        }
        Person owner = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        requireNotRetired(owner, date);
        Rollup.Figures before = rollup.figures(sheetId);
        LocalDate old = claim.getDate();
        claim.setDate(date);
        claims.flush();
        events.publishEvent(new ClaimRedated(sheetId, user, owner.getName(), claim.getAmount(), claim.getDate(),
                claim.getShownNumber(), old, date));
        rollup.changed(sheetId, user, before);
        return claim.getPersonId();
    }

    /**
     * Split an amount (more than 0.00 and less than the claim's amount) off an OPEN claim. The claim keeps
     * the rest and is released from any figure a statement bound it to; a new OPEN claim of the same person
     * takes the amount split off, with the same date and note, the next number, opened by the user and
     * due by its date plus the terms then of its person's grade on its date, naming the claim it was split
     * from, carrying its tags, and a late entry when that claim is. It is judged by the claim split alone: a claim not OPEN, a deferred one, one due by a date inside a closed period, an amount out of range, or a
     * split by a role the sheet's permission matrix does not allow to open a claim is refused and nothing
     * changes. Returns the person it belongs to.
     */
    @Transactional
    public long split(String sheetId, String user, long claimId, BigDecimal amount) {
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be split; only an OPEN one can");
        }
        if (Dues.deferred(claim, sheets.today(sheetId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The claim is deferred until " + claim.getDeferredUntil() + " and cannot be split");
        }
        if (!access.allows(sheetId, user, Permission.OPEN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Your role may not open a claim on this sheet");
        }
        if (amount.signum() <= 0 || amount.compareTo(claim.getAmount()) >= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The amount split off must be more than 0.00 and less than " + claim.getAmount().toPlainString());
        }
        Person owner = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Rollup.Figures before = rollup.figures(sheetId);
        claim.setAmount(claim.getAmount().subtract(amount));
        claim.unbind();
        Claim part = new Claim(sheetId, owner.getId(), amount, claim.getDate(), claim.getNote(), null);
        sheets.load(sheetId, user);
        part.setDueBy(holidays.dueBy(sheetId, claim.getDate(),
                sheets.settings(sheetId).termsFor(owner.getTerms(), owner.gradeOn(claim.getDate()), claim.getDate())));
        part.setSplitFrom(claim.getShownNumber());
        part.setTags(claim.getTags());
        part.setLateEntry(claim.isLateEntry());
        giveNumber(sheetId, part);
        part.setOpenedBy(user);
        part.setStatus(ClaimStatus.OPEN);
        log(sheetId, user, part, owner);
        claims.saveAndFlush(part);
        events.publishEvent(new ClaimSplit(sheetId, user, owner.getName(), claim.getAmount(), claim.getDate(),
                claim.getShownNumber(), part.getShownNumber()));
        events.publishEvent(new ClaimChanged("CLAIM_OPENED", sheetId, user, owner.getName(), part.getAmount(),
                part.getDate(), part.getShownNumber(), null));
        rollup.changed(sheetId, user, before);
        return owner.getId();
    }

    /**
     * Add a proof to a claim, raising its proofs count by one. A SETTLED or VOID claim, or a claim dated
     * inside a closed period, is refused and nothing changes. Returns the person it belongs to.
     */
    @Transactional
    public long addProof(String sheetId, String user, long claimId) {
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() == ClaimStatus.SETTLED || claim.getStatus() == ClaimStatus.VOID) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot take a proof");
        }
        claim.addProof();
        claims.flush();
        String person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).map(Person::getName).orElse("");
        events.publishEvent(new ClaimProofAdded(sheetId, user, person, claim.getAmount(), claim.getDate(),
                claim.getShownNumber(), claim.getProofs()));
        return claim.getPersonId();
    }

    /**
     * Remind an OPEN or PENDING claim: it takes the sheet's today as its reminded date. A claim can be
     * reminded once per sheet's today; a claim in any other status, one already reminded on the sheet's today,
     * or a claim due by a date inside a closed period is refused and nothing changes. Returns the person it
     * belongs to.
     */
    @Transactional
    public long remind(String sheetId, String user, long claimId) {
        Claim claim = claim(sheetId, claimId);
        if (!claim.getStatus().unpaid()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reminded; only an OPEN or PENDING one can");
        }
        LocalDate today = sheets.today(sheetId);
        if (today.equals(claim.getRemindedOn())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim was already reminded today (" + today + ")");
        }
        claim.setRemindedOn(today);
        claims.flush();
        String person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).map(Person::getName).orElse("");
        events.publishEvent(new ClaimReminded(sheetId, user, person, claim.getAmount(), claim.getDate(),
                claim.getShownNumber()));
        return claim.getPersonId();
    }

    /**
     * Refuse opening a claim with an empty note whose due (null: n/a) is above the sheet's note threshold in
     * force on the claim's date.
     */
    private void requireNote(String sheetId, Claim claim) {
        if (claim.getNote() != null && !claim.getNote().isBlank()) {
            return;
        }
        BigDecimal threshold = sheets.settings(sheetId).noteThreshold(claim.getDate());
        if (threshold == null) {
            return;
        }
        Person owner = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElse(null);
        BigDecimal due = dueNow(sheetId, claim, owner);
        if (due != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim with a due above " + threshold.toPlainString() + " needs a note to be opened");
        }
    }

    /**
     * Refuse settling or approving a claim with no proof whose due (null: n/a) is above the sheet's proof
     * threshold in force on the claim's date.
     */
    private void requireProof(String sheetId, Claim claim, BigDecimal due) {
        if (due == null || claim.getProofs() > 0) {
            return;
        }
        BigDecimal threshold = sheets.settings(sheetId).proofThreshold(claim.getDate());
        if (threshold != null && due.compareTo(threshold) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim with a due above " + threshold.toPlainString() + " needs a proof to be settled");
        }
    }

    /**
     * Approve a PENDING claim, settling it with its due fixed now (its live figure, late charge frozen in).
     * Only a steward approves, never the user who opened the claim; a claim not PENDING or due by a date inside a
     * closed period is refused and nothing changes. Returns the person it belongs to.
     */
    @Transactional
    public long approve(String sheetId, String user, long claimId) {
        Claim claim = pending(sheetId, user, claimId, "approve", "approved");
        if (user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The user who opened a claim cannot approve it");
        }
        Rollup.Figures before = rollup.figures(sheetId);
        Person owner = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElse(null);
        BigDecimal fixed = dueNow(sheetId, claim, owner);
        requireProof(sheetId, claim, fixed);
        claim.setSettledDue(fixed);
        claim.setStatus(ClaimStatus.SETTLED);
        log(sheetId, user, claim, owner);
        claims.flush();
        events.publishEvent(new ClaimChanged("CLAIM_APPROVED", sheetId, user, owner == null ? "" : owner.getName(),
                claim.getAmount(), claim.getDate(), claim.getShownNumber(), fixed == null ? "n/a" : fixed.toPlainString()));
        watches.notifyWatchers(sheetId, user, owner, claim.getShownNumber());
        rollup.changed(sheetId, user, before);
        return claim.getPersonId();
    }

    /**
     * Re-settle a SETTLED claim, fixing its due again at the figure it would have today as an OPEN claim bound
     * by no statement (today's rates, grades, floor and late charge), and logging it SETTLED again. Only a
     * steward re-settles; a claim not SETTLED or due by a date inside a closed period is refused and nothing changes.
     * Returns the person it belongs to.
     */
    @Transactional
    public long resettle(String sheetId, String user, long claimId) {
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.SETTLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be re-settled; only a SETTLED one can");
        }
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a steward can re-settle a claim");
        }
        Person owner = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Rollup.Figures before = rollup.figures(sheetId);
        BigDecimal old = claim.getSettledDue();
        BigDecimal fixed = Dues.asOpen(claim, owner::gradeOn, rates.table(sheetId));
        claim.setSettledDue(fixed);
        claim.logStatus(user, sheets.today(sheetId), fixed);
        claims.flush();
        events.publishEvent(new ClaimResettled(sheetId, user, owner.getName(), claim.getAmount(), claim.getDate(),
                claim.getShownNumber(), old, fixed));
        watches.notifyWatchers(sheetId, user, owner, claim.getShownNumber());
        rollup.changed(sheetId, user, before);
        return claim.getPersonId();
    }

    /**
     * Reject a PENDING claim with a reason, putting it back to OPEN (bound again by the last statement if
     * that statement counted it). Only a steward rejects; a claim not PENDING or due by a date inside a closed
     * period is refused and nothing changes. Returns the person it belongs to.
     */
    @Transactional
    public long reject(String sheetId, String user, long claimId, String reason) {
        Claim claim = pending(sheetId, user, claimId, "reject", "rejected");
        Rollup.Figures before = rollup.figures(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        Person owner = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElse(null);
        log(sheetId, user, claim, owner);
        claims.flush();
        String person = owner == null ? "" : owner.getName();
        events.publishEvent(new ClaimChanged("CLAIM_REJECTED", sheetId, user, person, claim.getAmount(),
                claim.getDate(), claim.getShownNumber(), null, reason));
        rollup.changed(sheetId, user, before);
        return claim.getPersonId();
    }

    /**
     * A steward moves a DRAFT, OPEN or PENDING claim to another person on the sheet. The claim keeps its
     * number and date, is released from any figure a statement bound it to (live again until its new
     * person's next statement), and lists as its new person's newest claim. A settled or voided claim, a
     * correction, a claim due by a date inside a closed period or after the new person's retirement date, or a move
     * by a role the sheet's permission matrix does not allow (a steward, unless set) is refused and nothing changes. Returns the people it moved from and to.
     */
    @Transactional
    public long[] reassign(String sheetId, String user, long claimId, long toPersonId) {
        Claim claim = claim(sheetId, claimId);
        if (!access.allows(sheetId, user, Permission.REASSIGN)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Your role may not reassign a claim on this sheet");
        }
        if (claim.getStatus() != ClaimStatus.DRAFT && claim.getStatus() != ClaimStatus.OPEN
                && claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be reassigned");
        }
        if (claim.isCorrection()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A correction belongs with the claim it corrects and cannot be reassigned");
        }
        Person from = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Person to = people.findByIdAndSheetId(toPersonId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Choose a person on the sheet"));
        if (from.getId().equals(to.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The claim is already " + to.getName() + "'s");
        }
        requireNotRetired(to, claim.getDate());
        Rollup.Figures before = rollup.figures(sheetId);
        claim.setPersonId(to.getId());
        claim.unbind();
        claims.flush();
        claims.relist(claim.getId());
        events.publishEvent(new ClaimReassigned(sheetId, user, to.getName(), claim.getAmount(), claim.getDate(),
                claim.getShownNumber(), from.getName(), to.getName()));
        rollup.changed(sheetId, user, before);
        return new long[] { from.getId(), to.getId() };
    }

    /** The PENDING claim a steward is to approve or reject; refused for anyone else or any other claim. */
    private Claim pending(String sheetId, String user, long claimId, String action, String done) {
        Claim claim = claim(sheetId, claimId);
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be " + done + "; only a PENDING one can");
        }
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a steward can " + action + " a claim");
        }
        return claim;
    }

    /**
     * A SETTLED claim may be voided only by a steward, and only while the sheet's today is no more than
     * 7 days after the date its status log records it SETTLED.
     */
    private void requireVoidableSettled(String sheetId, String user, Claim claim) {
        if (access.roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward may void a SETTLED claim");
        }
        LocalDate settledOn = claim.getLog().stream().filter(l -> l.getStatus() == ClaimStatus.SETTLED)
                .map(ClaimLogLine::getOn).reduce((a, b) -> b).orElse(null);
        LocalDate today = sheets.today(sheetId);
        if (settledOn == null || today.isAfter(settledOn.plusDays(7))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A SETTLED claim can only be voided within 7 days of its settlement"
                            + (settledOn == null ? "" : " (" + settledOn + ")"));
        }
    }

    private Claim claim(String sheetId, long claimId) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        periods.requireOpen(sheetId, claim.getDueBy());
        return claim;
    }

    /** Log the status a claim has just taken, on the sheet's today, with its due as it is now. */
    private void log(String sheetId, String user, Claim claim, Person owner) {
        BigDecimal due = owner == null ? (Dues.bound(claim) ? claim.getStatedDue() : claim.getSettledDue())
                : Dues.figure(claim, owner::gradeOn, rates.table(sheetId));
        claim.logStatus(user, sheets.today(sheetId), due);
    }

    /** The claim's due now, its late charge frozen in, as settling fixes it. */
    private BigDecimal dueNow(String sheetId, Claim claim, Person owner) {
        return owner == null ? (Dues.bound(claim) ? claim.getStatedDue() : null)
                : Dues.figure(claim, owner::gradeOn, rates.table(sheetId));
    }

    /**
     * Give a claim being opened its number: while the sheet's period numbering from is set and the
     * sheet's today is on or after it, the next in the period its due-by falls in (<period name>-<n>), or
     * in the sequence of claims in no period (X-<n>); otherwise the next sheet-wide number. Two users
     * taking one at the same moment wait on each other, so no number is given twice or skipped.
     */
    private void giveNumber(String sheetId, Claim claim) {
        if (!numberingOn(sheetId)) {
            claim.setNumber(sheets.nextClaimNumber(sheetId));
            return;
        }
        sheetRows.lockById(sheetId);
        Period period = periodOf(sheetId, claim.getDueBy());
        String sequence = sequenceOf(sheetId, claim.getDueBy());
        int n = claims.lastInSequence(sheetId, sequence) + 1;
        claim.setPeriodNumber(sequence, n, (period == null ? "X" : period.getName()) + "-" + n);
    }

    /** Whether the sheet's period numbering from is set and the sheet's today is on or after it. */
    private boolean numberingOn(String sheetId) {
        LocalDate from = sheets.periodNumberingFrom(sheetId);
        return from != null && !sheets.today(sheetId).isBefore(from);
    }

    /** The period whose dates contain the date, or null when none does. */
    private Period periodOf(String sheetId, LocalDate date) {
        return periods.list(sheetId).stream().filter(p -> p.contains(date)).findFirst().orElse(null);
    }

    /** The period sequence a claim due by the date counts in ('P<period id>', or 'X' for no period). */
    private String sequenceOf(String sheetId, LocalDate date) {
        Period period = periodOf(sheetId, date);
        return period == null ? "X" : "P" + period.getId();
    }

    /**
     * Refuse a correction (a negative amount) that does not name the number of a SETTLED claim of the
     * same person, and a claim that is not a correction but names one. Returns the date of the claim a
     * correction corrects (its rounding goes by it); null for any other claim.
     */
    private LocalDate requireCorrects(String sheetId, long personId, BigDecimal amount, String corrects) {
        if (amount.signum() >= 0) {
            if (corrects != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only a correction (a negative amount) names a claim it corrects");
            }
            return null;
        }
        if (corrects == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A negative amount is a correction; name the number of the settled claim it corrects");
        }
        return claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId).stream()
                .filter(c -> corrects.equals(c.getShownNumber()) && c.getStatus() == ClaimStatus.SETTLED)
                .map(Claim::getDate).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Claim " + corrects + " is not a settled claim of this person"));
    }

    /** Refuse a claim dated after its person's retirement date. */
    private static void requireNotRetired(Person person, LocalDate date) {
        if (person.isRetired() && date.isAfter(person.getRetiredFrom())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    person.getName() + " is retired from " + person.getRetiredFrom()
                            + "; a claim cannot be dated after that");
        }
    }
}
