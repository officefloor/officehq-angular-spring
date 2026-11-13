package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The claims people on a sheet make. */
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
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; a missing date takes the sheet's today. */
    @Transactional
    public Claim add(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + personId + " on sheet " + sheetId));
        LocalDate on = date != null ? date : sheets.today(sheetId);
        return claims.saveAndFlush(new Claim(sheetId, personId, scaled(amount), on, noteOf(note)));
    }

    /** Change a claim's amount, date and note; a missing date takes the sheet's today. */
    @Transactional
    public Claim update(String sheetId, Long personId, Long id, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetIdAndPersonId(id, sheetId, personId)
                .orElseThrow(() -> new NoSuchElementException("No claim " + id + " for person " + personId));
        claim.setAmount(scaled(amount));
        claim.setDate(date != null ? date : sheets.today(sheetId));
        claim.setNote(noteOf(note));
        return claims.saveAndFlush(claim);
    }

    private static BigDecimal scaled(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
