package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonNotFoundException;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The claims people on each sheet make. A claim left without a date is dated the sheet's today. */
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

    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate dated = date != null ? date : sheets.today(sheetId);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount, dated, noteOf(note)));
        record("CLAIM_ADDED", user, person, claim);
        return claim;
    }

    @Transactional
    public Claim update(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        claim.setAmount(amount);
        claim.setDate(date != null ? date : sheets.today(sheetId));
        claim.setNote(noteOf(note));
        claims.flush();
        Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                .orElseThrow(PersonNotFoundException::new);
        record("CLAIM_EDITED", user, person, claim);
        return claim;
    }

    private void record(String event, String user, Person person, Claim claim) {
        audit.change(event, claim.getSheetId(), user, "person=" + person.getName(),
                "amount=" + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                "date=" + claim.getDate());
    }

    private static String noteOf(String note) {
        return note == null ? "" : note.trim();
    }
}
