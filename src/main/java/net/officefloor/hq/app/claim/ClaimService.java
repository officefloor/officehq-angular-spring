package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
 * statuses one at a time. Settling a claim fixes its due at the rate its person's grade has then on the claim's date, or at the
 * figure a statement bound it to. No claim dated inside a closed period can be added, edited or moved.
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final SheetService sheets;
    private final RateService rates;
    private final Rollup rollup;
    private final PeriodService periods;
    private final ApplicationEventPublisher events;

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets,
            RateService rates, Rollup rollup, PeriodService periods, ApplicationEventPublisher events) {
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
        Rollup.Figures before = rollup.figures(sheetId);
        claims.saveAndFlush(new Claim(sheetId, personId, amount, on, note == null ? "" : note));
        events.publishEvent(new ClaimChanged("CLAIM_ADDED", sheetId, user, person.getName(), amount, on, null, null));
        rollup.changed(sheetId, user, before);
        return personId;
    }

    /**
     * Edit a claim's amount, date and note; refused when a closed period contains its date or the new one.
     * Returns the person it belongs to.
     */
    @Transactional
    public long edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        periods.requireOpen(sheetId, claim.getDate(), on);
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
     * the claim, or moving a claim dated inside a closed period, is refused and changes nothing.
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
        } else if (next == ClaimStatus.VOID) {
            claim.setNumber(null);
        }
        Person owner = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElse(null);
        String due = null;
        if (next == ClaimStatus.SETTLED) {
            BigDecimal fixed;
            if (claim.isStated()) {
                fixed = claim.getStatedDue();
            } else {
                BigDecimal pct = owner == null ? null : rates.table(sheetId).on(owner.getGrade(), claim.getDate());
                fixed = RateService.due(claim.getAmount(), pct);
            }
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
}
