package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.locks.ReentrantLock;
import net.officefloor.hq.app.asof.SheetHistory;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settling a claim as of a date on or before the sheet's today: its frozen figure is its due as it
 * stood at the end of that date, worked out as the as-of view does (the sheet put back as it stood
 * then, with that date as its today, in a transaction rolled back once read). A date after today, or
 * one on which the claim did not exist yet, is refused.
 */
@Service
public class ClaimSettleAsOfService {

    /** The claim's due at the end of the date: whether it existed then, and its figure (null: none). */
    private record Then(boolean existed, BigDecimal figure) {}

    private final ClaimService service;
    private final ClaimRepository claims;
    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetHistory history;
    private final TransactionTemplate transactions;

    public ClaimSettleAsOfService(ClaimService service, ClaimRepository claims, PersonRepository people,
            SheetService sheets, SheetHistory history, PlatformTransactionManager transactions) {
        this.service = service;
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
        this.history = history;
        this.transactions = new TransactionTemplate(transactions);
    }

    public Claim settle(String sheetId, String user, Long id, LocalDate on) {
        if (on.isAfter(sheets.today(sheetId))) throw new ClaimSettleDateException();
        Then then = dueAt(sheetId, id, on);
        if (!then.existed()) throw new ClaimSettleDateException();
        return service.settleAsOf(sheetId, user, id, on, then.figure());
    }

    private Then dueAt(String sheetId, Long id, LocalDate on) {
        if (!history.exists(sheetId)) return new Then(false, null);
        ReentrantLock lock = history.lock(sheetId);
        lock.lock();
        try {
            return transactions.execute(status -> {
                status.setRollbackOnly();
                history.restore(sheetId, on);
                Claim claim = claims.findByIdAndSheetId(id, sheetId).orElse(null);
                if (claim == null) return new Then(false, null);
                Person person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).orElse(null);
                if (person == null) return new Then(false, null);
                return new Then(true, service.chargedFigureOf(sheetId, person, claim));
            });
        } finally {
            lock.unlock();
        }
    }
}
