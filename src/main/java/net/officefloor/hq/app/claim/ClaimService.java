package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets) {
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
    }

    /** The sheet's claims, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        sheets.today(sheetId); // the sheet must exist
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; a claim without a date is dated the sheet's today. */
    @Transactional
    public Claim add(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        LocalDate today = sheets.today(sheetId);
        people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        return claims.save(new Claim(sheetId, personId, amount, date != null ? date : today, noteOf(note)));
    }

    @Transactional
    public Claim update(String sheetId, Long id, BigDecimal amount, LocalDate date, String note) {
        LocalDate today = sheets.today(sheetId);
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        claim.setAmount(amount);
        claim.setDate(date != null ? date : today);
        claim.setNote(noteOf(note));
        return claim;
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
