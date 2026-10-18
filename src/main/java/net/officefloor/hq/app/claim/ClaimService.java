package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    /** Add a claim for a person; without a date it is dated the sheet's today. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Claim saved = claims.saveAndFlush(new Claim(sheetId, personId, amount, dateOrToday(sheetId, date), noteOf(note)));
        audit(sheetId, user, "CLAIM_ADDED", person, saved);
        return saved;
    }

    /** Change a claim's amount, date and note; without a date it is dated the sheet's today. */
    @Transactional
    public Claim edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        claim.setAmount(amount);
        claim.setDate(dateOrToday(sheetId, date));
        claim.setNote(noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(saved.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        audit(sheetId, user, "CLAIM_EDITED", person, saved);
        return saved;
    }

    /** Move a claim to its next status; a change its current status does not allow is refused. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus next) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        if (!claim.getStatus().canBecome(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next);
        }
        claim.setStatus(next);
        Claim saved = claims.saveAndFlush(claim);
        Person person = people.findByIdAndSheetId(saved.getPersonId(), sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            default -> "CLAIM_VOIDED";
        };
        audit(sheetId, user, event, person, saved);
        return saved;
    }

    /** Amounts are written as the screen shows them (two decimals), dates as yyyy-mm-dd. */
    private void audit(String sheetId, String user, String event, Person person, Claim claim) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate());
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
