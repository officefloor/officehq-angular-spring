package net.officefloor.hq.app;

import java.math.RoundingMode;
import net.officefloor.hq.app.claim.ClaimChanged;
import net.officefloor.hq.app.person.PersonChanged;
import net.officefloor.hq.app.rate.RateChanged;
import net.officefloor.hq.app.statement.StatementTaken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Writes one audit line per change to a sheet, once the change is committed: a refused change
 * rolls back and writes nothing. Each line is the event, {@code sheet=} and {@code by=}, then the
 * event's fields.
 */
@Component
public class SheetAudit {

    private final Audit audit;

    public SheetAudit(Audit audit) {
        this.audit = audit;
    }

    @TransactionalEventListener
    public void on(PersonChanged e) {
        audit.record(e.event() + " sheet=" + e.sheetId() + " by=" + e.user()
                + " name=" + e.name() + " grade=" + e.grade());
    }

    @TransactionalEventListener
    public void on(ClaimChanged e) {
        audit.record(e.event() + " sheet=" + e.sheetId() + " by=" + e.user()
                + " person=" + e.person() + " amount=" + e.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " date=" + e.date() + (e.number() == null ? "" : " number=" + e.number())
                + (e.due() == null ? "" : " due=" + e.due()));
    }

    @TransactionalEventListener
    public void on(RateChanged e) {
        audit.record("RATE_SET sheet=" + e.sheetId() + " by=" + e.user()
                + " grade=" + e.grade() + " rate=" + e.rate().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + (e.from() == null ? "" : " from=" + e.from()));
    }

    @TransactionalEventListener
    public void on(StatementTaken e) {
        audit.record("STATEMENT sheet=" + e.sheetId() + " by=" + e.user() + " name=" + e.name()
                + " due-total=" + (e.dueTotal() == null ? "n/a"
                        : e.dueTotal().setScale(2, RoundingMode.HALF_UP).toPlainString())
                + " claims=" + e.claims());
    }
}
