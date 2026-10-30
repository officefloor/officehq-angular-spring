package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.period.PeriodService;
import net.officefloor.hq.app.person.PersonRepository;
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
 * its number, which is never reused. Settling a claim fixes its due at the rate its person's grade has then on the claim's date
 * (at least the sheet's floor then), or at the
 * figure a statement bound it to, plus the late charge it has then. No claim dated inside a closed period can be added, edited or moved,
 * and no claim dated after its person's retirement date can be added or edited to that date. A clerk
 * cannot settle a claim whose due is above the sheet's clerk limit; a steward settles anything.
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final SheetService sheets;
    private final RateService rates;
    private final Rollup rollup;
    private final PeriodService periods;
    private final AccessService access;
    private final ApplicationEventPublisher events;

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets,
            RateService rates, Rollup rollup, PeriodService periods, AccessService access,
            ApplicationEventPublisher events) {
        this.access = access;
        this.rollup = rollup;
        this.periods = periods;
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
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
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        periods.requireOpen(sheetId, on);
        requireNotRetired(person, on);
        Rollup.Figures before = rollup.figures(sheetId);
        claims.saveAndFlush(new Claim(sheetId, personId, amount, on, note == null ? "" : note));
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
    public long edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note) {
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
        Rollup.Figures before = rollup.figures(sheetId);
        claim.setAmount(amount);
        claim.setDate(on);
        claim.setNote(note == null ? "" : note);
        claims.flush();
        String person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).map(Person::getName).orElse("");
        events.publishEvent(new ClaimChanged("CLAIM_EDITED", sheetId, user, person, claim.getAmount(),
                claim.getDate(), claim.getNumber(), null));
        rollup.changed(sheetId, user, before);
        return claim.getPersonId();
    }

    /**
     * Move a claim to a status; a move its status does not allow, settling by the user who opened
     * the claim, settling by a clerk a claim whose due is above the clerk limit, or moving a claim dated inside a closed period, is refused and changes nothing.
     * Returns the person it belongs to.
     */
    @Transactional
    public long move(String sheetId, String user, long claimId, ClaimStatus next) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        periods.requireOpen(sheetId, claim.getDate());
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next);
        }
        if (next == ClaimStatus.SETTLED && user.equals(claim.getOpenedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "The user who opened a claim cannot settle it");
        }
        Rollup.Figures before = rollup.figures(sheetId);
        Integer number = claim.getNumber();
        if (next == ClaimStatus.OPEN) {
            number = sheets.nextClaimNumber(sheetId);
            claim.setNumber(number);
            claim.setOpenedBy(user);
        }
        Person owner = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElse(null);
        String due = null;
        if (next == ClaimStatus.SETTLED) {
            // The due now, its late charge frozen in.
            BigDecimal fixed = owner == null ? (claim.isStated() ? claim.getStatedDue() : null)
                    : Dues.figure(claim, owner::gradeOn, rates.table(sheetId));
            access.requireCanSettle(sheetId, user, fixed);
            claim.setSettledDue(fixed);
            due = fixed == null ? "n/a" : fixed.toPlainString();
        }
        claim.setStatus(next);
        claims.flush();
        String person = owner == null ? "" : owner.getName();
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        events.publishEvent(new ClaimChanged(event, sheetId, user, person, claim.getAmount(), claim.getDate(), number, due));
        rollup.changed(sheetId, user, before);
        return claim.getPersonId();
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
