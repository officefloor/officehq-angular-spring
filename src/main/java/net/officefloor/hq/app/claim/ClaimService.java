package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

/** The claims people make on a sheet. */
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

    /** All the claims on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Adds a claim for the person; a missing date takes the sheet's today. */
    @Transactional
    public Claim add(String by, String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = person(sheetId, personId);
        Claim added = claims.saveAndFlush(
                new Claim(sheetId, personId, scaled(amount), dateOrToday(sheetId, date), noteOf(note)));
        audit.recordOnCommit(record("CLAIM_ADDED", by, person, added));
        return added;
    }

    @Transactional
    public Claim update(String by, String sheetId, Long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        claim.setAmount(scaled(amount));
        claim.setDate(dateOrToday(sheetId, date));
        claim.setNote(noteOf(note));
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_EDITED", by, person(sheetId, saved.getPersonId()), saved));
        return saved;
    }

    /** Moves the claim to the status; a change its status does not allow is refused. */
    @Transactional
    public Claim changeStatus(String by, String sheetId, Long id, ClaimStatus next) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        if (!claim.getStatus().canMoveTo(next)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + next + ".");
        }
        claim.setStatus(next);
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (next) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException();
        };
        audit.recordOnCommit(record(event, by, person(sheetId, saved.getPersonId()), saved));
        return saved;
    }

    private Person person(String sheetId, Long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
    }

    private static String record(String event, String by, Person person, Claim c) {
        return event + " sheet=" + c.getSheetId() + " by=" + by + " person=" + person.getName()
                + " amount=" + c.getAmount().toPlainString() + " date=" + c.getDate();
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.load(sheetId).getToday();
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.strip();
    }
}
