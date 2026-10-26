package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
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

    public ClaimService(ClaimRepository claims, PersonRepository people, SheetService sheets) {
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
    }

    /** All the claims on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Adds a claim for the person; a missing date takes the sheet's today. */
    @Transactional
    public Claim add(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        if (people.findByIdAndSheetId(personId, sheetId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person.");
        }
        return claims.saveAndFlush(new Claim(sheetId, personId, scaled(amount), dateOrToday(sheetId, date), noteOf(note)));
    }

    @Transactional
    public Claim update(String sheetId, Long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim."));
        claim.setAmount(scaled(amount));
        claim.setDate(dateOrToday(sheetId, date));
        claim.setNote(noteOf(note));
        return claims.saveAndFlush(claim);
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
