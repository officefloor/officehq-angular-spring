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

/**
 * The claims kept on a sheet, newest first. A claim without a date takes the sheet's today; a
 * missing note is stored empty.
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
    public Claim add(String sheetId, String user, Long personId, BigDecimal amount, LocalDate date, String note) {
        sheets.load(sheetId, user);
        if (people.findByIdAndSheetId(personId, sheetId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person");
        }
        return claims.save(new Claim(sheetId, personId, scaled(amount),
                date != null ? date : sheets.today(sheetId), note != null ? note : ""));
    }

    @Transactional
    public Claim update(String sheetId, Long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        claim.setAmount(scaled(amount));
        claim.setDate(date != null ? date : sheets.today(sheetId));
        claim.setNote(note != null ? note : "");
        return claim;
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
