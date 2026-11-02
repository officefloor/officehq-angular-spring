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

/** The claims people make on a sheet. */
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

    /** The claims on the sheet, newest first. */
    @Transactional(readOnly = true)
    public List<Claim> list(String sheetId) {
        return claims.findBySheetIdOrderByIdDesc(sheetId);
    }

    /** Add a claim for a person; a date left out takes the sheet's today. */
    @Transactional
    public Claim add(String sheetId, String user, long personId, BigDecimal amount, LocalDate date, String note) {
        Person person = person(sheetId, personId);
        LocalDate on = date != null ? date : sheets.today(sheetId);
        Claim claim = claims.saveAndFlush(new Claim(sheetId, personId, amount, on, note));
        audit.recordOnCommit(record("CLAIM_ADDED", sheetId, user, person, claim));
        return claim;
    }

    /** Change a claim's amount, date and note; a date left out takes the sheet's today. */
    @Transactional
    public Claim edit(String sheetId, String user, long claimId, BigDecimal amount, LocalDate date, String note) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        claim.setAmount(amount);
        claim.setDate(date != null ? date : sheets.today(sheetId));
        claim.setNote(note);
        Claim saved = claims.saveAndFlush(claim);
        audit.recordOnCommit(record("CLAIM_EDITED", sheetId, user, person(sheetId, saved.getPersonId()), saved));
        return saved;
    }

    /** Move a claim to a new status; a change its current status does not allow is refused. */
    @Transactional
    public Claim changeStatus(String sheetId, String user, long claimId, ClaimStatus to) {
        Claim claim = claims.lockByIdAndSheetId(claimId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim on this sheet"));
        if (!claim.getStatus().canBecome(to)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A " + claim.getStatus() + " claim cannot become " + to);
        }
        claim.setStatus(to);
        Claim saved = claims.saveAndFlush(claim);
        String event = switch (to) {
            case OPEN -> "CLAIM_OPENED";
            case SETTLED -> "CLAIM_SETTLED";
            case VOID -> "CLAIM_VOIDED";
            case DRAFT -> throw new IllegalStateException("a claim never returns to DRAFT");
        };
        audit.recordOnCommit(record(event, sheetId, user, person(sheetId, saved.getPersonId()), saved));
        return saved;
    }

    private Person person(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
    }

    private static String record(String event, String sheetId, String user, Person person, Claim claim) {
        return event + " sheet=" + sheetId + " by=" + user + " person=" + person.getName()
                + " amount=" + claim.getAmount().setScale(2).toPlainString() + " date=" + claim.getDate();
    }
}
