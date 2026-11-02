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

    /** The claims on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; a date left out takes the sheet's today. */
    @Transactional
    public Claim add(String sheetId, long personId, BigDecimal amount, LocalDate date, String note) {
        if (people.findByIdAndSheetId(personId, sheetId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet");
        }
        LocalDate on = date != null ? date : sheets.today(sheetId);
        return claims.saveAndFlush(new Claim(sheetId, personId, amount, on, note));
    }

    /** Change a claim's amount, date and note; a date left out takes the sheet's today. */
    @Transactional
    public Claim edit(String sheetId, long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        claim.setAmount(amount);
        claim.setDate(date != null ? date : sheets.today(sheetId));
        claim.setNote(note);
        return claims.saveAndFlush(claim);
    }
}
