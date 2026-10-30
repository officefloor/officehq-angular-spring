package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The claims on a sheet: listed per person newest first, added and edited one at a time. */
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

    /** Every claim on the sheet by the person who made it, each person's newest first. */
    @Transactional(readOnly = true)
    public Map<Long, List<Claim>> bySheet(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId).stream()
                .collect(Collectors.groupingBy(Claim::getPersonId));
    }

    @Transactional(readOnly = true)
    public List<Claim> forPerson(String sheetId, long personId) {
        return claims.findBySheetIdAndPersonIdOrderByIdDesc(sheetId, personId);
    }

    /** Add a claim; a date left out takes the sheet's today. Returns the person it belongs to. */
    @Transactional
    public long add(String sheetId, long personId, BigDecimal amount, LocalDate date, String note) {
        if (people.findByIdAndSheetId(personId, sheetId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person");
        }
        LocalDate on = date != null ? date : sheets.today(sheetId);
        claims.saveAndFlush(new Claim(sheetId, personId, amount, on, note == null ? "" : note));
        return personId;
    }

    /** Edit a claim's amount, date and note. Returns the person it belongs to. */
    @Transactional
    public long edit(String sheetId, long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        claim.setAmount(amount);
        claim.setDate(date != null ? date : sheets.today(sheetId));
        claim.setNote(note == null ? "" : note);
        claims.flush();
        return claim.getPersonId();
    }
}
