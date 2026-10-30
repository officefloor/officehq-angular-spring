package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import net.officefloor.hq.app.person.Person;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The claims on a sheet: listed per person newest first, added, edited and moved through their
 * statuses one at a time.
 */
@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final SheetService sheets;
    private final ApplicationEventPublisher events;

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets,
            ApplicationEventPublisher events) {
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
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
        claims.saveAndFlush(new Claim(sheetId, personId, amount, on, note == null ? "" : note));
        events.publishEvent(new ClaimChanged("CLAIM_ADDED", sheetId, user, person.getName(), amount, on));
        return personId;
    }

    /** Edit a claim's amount, date and note. Returns the person it belongs to. */
    @Transactional
    public long edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        claim.setAmount(amount);
        claim.setDate(date != null ? date : sheets.today(sheetId));
        claim.setNote(note == null ? "" : note);
        claims.flush();
        String person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).map(Person::getName).orElse("");
        events.publishEvent(new ClaimChanged("CLAIM_EDITED", sheetId, user, person, claim.getAmount(),
                claim.getDate()));
        return claim.getPersonId();
    }

    /**
     * Move a claim to a status; a move its status does not allow is refused and changes nothing.
     * Returns the person it belongs to.
     */
    @Transactional
    public long move(String sheetId, String user, long claimId, ClaimStatus next) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next);
        }
        claim.setStatus(next);
        claims.flush();
        String person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).map(Person::getName).orElse("");
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        events.publishEvent(new ClaimChanged(event, sheetId, user, person, claim.getAmount(), claim.getDate()));
        return claim.getPersonId();
    }
}
