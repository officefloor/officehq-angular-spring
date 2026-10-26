package net.officefloor.hq.app.claim;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.asof.SheetHistory;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * A claim's due as it stood at the end of a date, as the as-of view works it out: in a transaction of
 * its own that puts the sheet back to what {@link SheetHistory} kept for that date (with the date as its
 * today) and is always rolled back, so the store and the audit file are left as they were. It runs before
 * the change that uses it takes any lock on the sheet's rows, which putting the sheet back rewrites.
 */
@Service
public class ClaimAsOfService {

    /** The claim's due at the end of a date; null when it was n/a. */
    public record DueAsOf(BigDecimal due) {
    }

    private final SheetHistory history;
    private final SheetService sheets;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final ClaimService service;
    private final TransactionTemplate transaction;
    @PersistenceContext
    private EntityManager entities;

    public ClaimAsOfService(SheetHistory history, SheetService sheets, ClaimRepository claims, PersonRepository people,
            ClaimService service, PlatformTransactionManager transactions) {
        this.history = history;
        this.sheets = sheets;
        this.claims = claims;
        this.people = people;
        this.service = service;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** The claim's due at the end of the date; refused (422) for a date after the sheet's today, and for a
     *  claim that did not exist at the end of it. */
    public DueAsOf dueAsOf(String sheetId, long id, LocalDate on) {
        return transaction.execute((status) -> {
            status.setRollbackOnly();
            if (claims.findByIdAndSheetId(id, sheetId).isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim.");
            }
            LocalDate today = sheets.today(sheetId);
            if (today == null || on.isAfter(today)) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "A claim can only be settled as of a date on or before the sheet's today.");
            }
            history.restoreWithParents(sheetId, on);
            // What was read before the sheet was put back is no longer what it holds.
            entities.clear();
            Claim claim = claims.findByIdAndSheetId(id, sheetId).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "The claim did not exist on " + on + "."));
            return new DueAsOf(people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                    .map((person) -> service.due(sheetId, person, claim))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "The claim did not exist on " + on + ".")));
        });
    }
}
