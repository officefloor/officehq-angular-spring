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

/**
 * The claims on a sheet: added to a person and edited (amount, date, note). A claim given no date
 * takes the sheet's today.
 */
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

    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, long personId, BigDecimal amount, LocalDate date, String note) {
        if (people.findByIdAndSheetId(personId, sheetId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet.");
        }
        return claims.saveAndFlush(new Claim(sheetId, personId, amount, dateOrToday(sheetId, date), note));
    }

    @Transactional
    public Claim update(String sheetId, long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet."));
        claim.setAmount(amount);
        claim.setDate(dateOrToday(sheetId, date));
        claim.setNote(note);
        return claims.saveAndFlush(claim);
    }

    private LocalDate dateOrToday(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }
}
