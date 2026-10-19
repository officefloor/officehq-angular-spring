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
        sheets.today(sheetId); // the sheet must exist
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; a claim without a date is dated the sheet's today. */
    @Transactional
    public Claim add(String user, String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        LocalDate today = sheets.today(sheetId);
        Person person = requirePerson(sheetId, personId);
        Claim claim = claims.saveAndFlush(
                new Claim(sheetId, personId, amount, date != null ? date : today, noteOf(note)));
        audit.recordOnCommit(entry("CLAIM_ADDED", user, person, claim));
        return claim;
    }

    @Transactional
    public Claim update(String user, String sheetId, Long id, BigDecimal amount, LocalDate date, String note) {
        LocalDate today = sheets.today(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        claim.setAmount(amount);
        claim.setDate(date != null ? date : today);
        claim.setNote(noteOf(note));
        claims.flush();
        audit.recordOnCommit(entry("CLAIM_EDITED", user, requirePerson(sheetId, claim.getPersonId()), claim));
        return claim;
    }

    private Person requirePerson(String sheetId, Long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
    }

    /** The amount is written as the screen shows it, with two decimals; the date as yyyy-mm-dd. */
    private static String entry(String event, String user, Person person, Claim claim) {
        return event + " sheet=" + claim.getSheetId() + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate();
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
