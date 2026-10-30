package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.access.Role;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.rate.RateService;
import net.officefloor.hq.app.rollup.Rollup;
import org.springframework.context.ApplicationEventPublisher;
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
 * figure a statement bound it to, plus the late charge it has then. No claim dated inside a closed period can be added, edited or moved,
 * and no claim dated after its person's retirement date can be added or edited to that date. A clerk
 * settling a claim whose due is above the sheet's clerk limit leaves it PENDING instead; a steward settles
 * anything, and approves a PENDING claim (settling it, its due fixed then) or rejects it with a reason
 * (back to OPEN); the user who opened a claim cannot approve it. A claim
 * with a negative amount is a correction: it is added or edited only when it names, by number, a SETTLED
 * claim of the same person, and only a correction names one.
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
    private final ApplicationEventPublisher events;

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets,
            SheetRepository sheetRows, RateService rates, Rollup rollup, PeriodService periods, AccessService access,
            ApplicationEventPublisher events) {
        this.access = access;
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
        return claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .collect(Collectors.groupingBy(Claim::getPersonId));
    }

    @Transactional(readOnly = true)
    public List<Claim> forPerson(String sheetId, long personId) {
        return claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
    }

    /** Add a claim; a date left out takes the sheet's today. Returns the person it belongs to. */
    @Transactional
    public long add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        return add(sheetId, user, personId, amount, date, note, null);
    }

    /**
     * Add a claim, a correction naming the number of the claim it corrects; a date left out takes the
     * sheet's today. Returns the person it belongs to.
     */
    @Transactional
    public long add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        periods.requireOpen(sheetId, on);
        requireNotRetired(person, on);
        LocalDate correctsDate = requireCorrects(sheetId, personId, amount, corrects);
        Rollup.Figures before = rollup.figures(sheetId);
        Claim claim = new Claim(sheetId, personId, amount, on, note == null ? "" : note, corrects);
        claim.setCorrectsDate(correctsDate);
        claims.saveAndFlush(claim);
        events.publishEvent(new ClaimChanged("CLAIM_ADDED", sheetId, user, person.getName(), amount, on, null, null));
        rollup.changed(sheetId, user, before);
        return personId;
    }

    /**
     * Edit a claim's amount, date and note; only a DRAFT can be edited (an open claim is voided and
     * added again instead), and an edit is refused when a closed period contains its date or the new one.
     * Returns the person it belongs to.
     */
    @Transactional
    public long edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note,
            String corrects) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        periods.requireOpen(sheetId, claim.getDate(), on);
        if (claim.getStatus() != ClaimStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot be edited; void it and add a new one");
        }
        if (!on.equals(claim.getDate())) {
            people.findByIdAndSheetId(claim.getPersonId(), sheetId).ifPresent(p -> requireNotRetired(p, on));
        }
        LocalDate correctsDate = requireCorrects(sheetId, claim.getPersonId(), amount, corrects);
        Rollup.Figures before = rollup.figures(sheetId);
        claim.setAmount(amount);
        claim.setCorrects(corrects);
        claim.setCorrectsDate(correctsDate);
        claim.setDate(on);
        claim.setNote(note == null ? "" : note);
        claims.flush();
        String person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).map(Person::getName).orElse("");
        events.publishEvent(new ClaimChanged("CLAIM_EDITED", sheetId, user, person, claim.getAmount(),
                claim.getDate(), claim.getShownNumber(), null));
        rollup.changed(sheetId, user, before);
        return claim.getPersonId();
    }

    /**
     * Move a claim to a status; a move its status does not allow, settling by the user who opened
     * the claim, or moving a claim dated inside a closed period, is refused and changes nothing. Settling
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
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot settle it");
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
            if (access.canSettle(sheetId, user, fixed)) {
                claim.setSettledDue(fixed);
                due = fixed == null ? "n/a" : fixed.toPlainString();
            } else {
                next = ClaimStatus.PENDING;
            }
        }
        claim.setStatus(next);
        claims.flush();
        String person = owner == null ? "" : owner.getName();
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case PENDING -> "CLAIM_PENDING";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        events.publishEvent(new ClaimChanged(event, sheetId, user, person, claim.getAmount(), claim.getDate(), number, due));
        rollup.changed(sheetId, user, before);
        return claim.getPersonId();
    }

    /**
     * Approve a PENDING claim, settling it with its due fixed now (its live figure, late charge frozen in).
     * Only a steward approves, never the user who opened the claim; a claim not PENDING or dated inside a
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
        claim.setSettledDue(fixed);
        claim.setStatus(ClaimStatus.SETTLED);
        claims.flush();
        events.publishEvent(new ClaimChanged("CLAIM_APPROVED", sheetId, user, owner == null ? "" : owner.getName(),
                claim.getAmount(), claim.getDate(), claim.getShownNumber(), fixed == null ? "n/a" : fixed.toPlainString()));
        rollup.changed(sheetId, user, before);
        return claim.getPersonId();
    }

    /**
     * Reject a PENDING claim with a reason, putting it back to OPEN (bound again by the last statement if
     * that statement counted it). Only a steward rejects; a claim not PENDING or dated inside a closed
     * period is refused and nothing changes. Returns the person it belongs to.
     */
    @Transactional
    public long reject(String sheetId, String user, long claimId, String reason) {
        Claim claim = pending(sheetId, user, claimId, "reject", "rejected");
        Rollup.Figures before = rollup.figures(sheetId);
        claim.setStatus(ClaimStatus.OPEN);
        claims.flush();
        String person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).map(Person::getName).orElse("");
        events.publishEvent(new ClaimChanged("CLAIM_REJECTED", sheetId, user, person, claim.getAmount(),
                claim.getDate(), claim.getShownNumber(), null, reason));
        rollup.changed(sheetId, user, before);
        return claim.getPersonId();
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

    private Claim claim(String sheetId, long claimId) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        periods.requireOpen(sheetId, claim.getDate());
        return claim;
    }

    /** The claim's due now, its late charge frozen in, as settling fixes it. */
    private BigDecimal dueNow(String sheetId, Claim claim, Person owner) {
        return owner == null ? (Dues.bound(claim) ? claim.getStatedDue() : null)
                : Dues.figure(claim, owner::gradeOn, rates.table(sheetId));
    }

    /**
     * Give a claim being opened its number: while the sheet's period numbering from is set and the
     * sheet's today is on or after it, the next in the period its date falls in (<period name>-<n>), or
     * in the sequence of claims in no period (X-<n>); otherwise the next sheet-wide number. Two users
     * taking one at the same moment wait on each other, so no number is given twice or skipped.
     */
    private void giveNumber(String sheetId, Claim claim) {
        LocalDate from = sheets.periodNumberingFrom(sheetId);
        if (from == null || sheets.today(sheetId).isBefore(from)) {
            claim.setNumber(sheets.nextClaimNumber(sheetId));
            return;
        }
        sheetRows.lockById(sheetId);
        Period period = periods.list(sheetId).stream().filter(p -> p.contains(claim.getDate()))
                .findFirst().orElse(null);
        String sequence = period == null ? "X" : "P" + period.getId();
        int n = claims.lastInSequence(sheetId, sequence) + 1;
        claim.setPeriodNumber(sequence, n, (period == null ? "X" : period.getName()) + "-" + n);
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
