package net.officefloor.hq.app.claim;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.AsOfFilter;
import net.officefloor.hq.app.sheet.SheetHistory;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * A claim's due as it stood at the end of a day: worked out as the as-of view works it out (see
 * {@link AsOfFilter}), from the sheet restored as it stood then, its today the date, in a transaction
 * that is then rolled back.
 */
@Service
public class ClaimAsOf {

    private final ClaimRepository claims;
    private final PersonRepository people;
    private final ClaimService service;
    private final SheetService sheets;
    private final SheetHistory history;
    private final EntityManager entities;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate rolledBack;

    public ClaimAsOf(ClaimRepository claims, PersonRepository people, ClaimService service, SheetService sheets,
            SheetHistory history, EntityManager entities, PlatformTransactionManager transactions) {
        this.claims = claims;
        this.people = people;
        this.service = service;
        this.sheets = sheets;
        this.history = history;
        this.entities = entities;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.rolledBack = new TransactionTemplate(transactions);
    }

    /**
     * The claim's due at the end of {@code day} (null when it had none). Refused (409) for a day after
     * the sheet's today, and for a claim that was not on the sheet then.
     */
    public BigDecimal due(String sheetId, long id, LocalDate day) {
        LocalDate today = readOnly.execute(status -> sheets.today(sheetId));
        if (day.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A claim cannot be settled as of a date after the sheet's today.");
        }
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        Object was = request == null ? null : request.getAttribute(AsOfFilter.AS_OF, RequestAttributes.SCOPE_REQUEST);
        if (request != null) {
            // the sheet's ancestors are read as of the day too, as the as-of view reads them
            request.setAttribute(AsOfFilter.AS_OF, day, RequestAttributes.SCOPE_REQUEST);
        }
        try {
            return rolledBack.execute(status -> {
                status.setRollbackOnly();
                history.restore(sheetId, day);
                // the rows were put back behind the persistence context's back: read them afresh
                entities.clear();
                Claim claim = claims.findByIdAndSheetId(id, sheetId)
                        .filter(c -> c.getStatus() != ClaimStatus.DELETED)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                                "This claim was not on the sheet on " + day + "."));
                Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                                "This claim was not on the sheet on " + day + "."));
                BigDecimal due = service.charged(sheetId, person, claim);
                entities.clear();
                return due;
            });
        } finally {
            if (request != null) {
                if (was == null) {
                    request.removeAttribute(AsOfFilter.AS_OF, RequestAttributes.SCOPE_REQUEST);
                } else {
                    request.setAttribute(AsOfFilter.AS_OF, was, RequestAttributes.SCOPE_REQUEST);
                }
            }
        }
    }
}
