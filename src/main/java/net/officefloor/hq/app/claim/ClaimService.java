package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the people's claims on a sheet. A claim with no date is dated the sheet's today. */
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

    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    @Transactional
    public Claim add(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        if (people.findByIdAndSheetId(personId, sheetId).isEmpty()) {
            throw new PersonNotFoundException(sheetId, personId);
        }
        return claims.saveAndFlush(new Claim(sheetId, personId, amount, dateOr(sheetId, date), noteOf(note)));
    }

    @Transactional
    public Claim update(String sheetId, Long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ClaimNotFoundException(sheetId, id));
        claim.setAmount(amount);
        claim.setDate(dateOr(sheetId, date));
        claim.setNote(noteOf(note));
        return claims.saveAndFlush(claim);
    }

    private LocalDate dateOr(String sheetId, LocalDate date) {
        return date != null ? date : sheets.today(sheetId);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
