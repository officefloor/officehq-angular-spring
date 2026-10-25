package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClaimService {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets, Audit audit) {
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim to a person; a claim with no date is dated the sheet's today. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = person(sheetId, personId);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount, dateOr(sheetId, date), noteOf(note)));
        recordChange("CLAIM_ADDED", user, person, claim);
        return claim;
    }

    /** Change a claim's amount, date and note; a blank date takes the sheet's today. */
    @Transactional
    public Claim update(String sheetId, String user, long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        claim.set(amount, dateOr(sheetId, date), noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        recordChange("CLAIM_EDITED", user, person(sheetId, saved.getPersonId()), saved);
        return saved;
    }

    /** Move a claim to a new status; a change its status does not allow is refused. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long id, ClaimStatus next) {
        Claim claim = claims.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        Integer number = next == ClaimStatus.OPEN ? Integer.valueOf(sheets.nextClaimNumber(sheetId)) : claim.getNumber();
        claim.setStatus(next);
        // A void claim keeps no number; the number it had is never given to another claim.
        claim.setNumber(next == ClaimStatus.VOID ? null : number);
        Claim saved = claims.saveAndFlush(claim);
        recordChange(EVENTS.get(next), user, person(sheetId, saved.getPersonId()), saved, number);
        return saved;
    }

    private static final Map<ClaimStatus, String> EVENTS = Map.of(
            ClaimStatus.OPEN, "CLAIM_OPENED", ClaimStatus.SETTLED, "CLAIM_SETTLED", ClaimStatus.VOID, "CLAIM_VOIDED");

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
    }

    private void recordChange(String event, String user, Person person, Claim claim) {
        recordChange(event, user, person, claim, claim.getNumber());
    }

    /** The claim's audit line; a claim with a number (or that had one, when voided) carries it at the end. */
    private void recordChange(String event, String user, Person person, Claim claim, Integer number) {
        audit.recordOnCommit(event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().toPlainString() + " date=" + claim.getDate()
                + (number == null ? "" : " number=" + number));
    }

    private LocalDate dateOr(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
