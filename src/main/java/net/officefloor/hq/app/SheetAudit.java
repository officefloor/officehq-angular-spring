package net.officefloor.hq.app;

import java.math.RoundingMode;
import net.officefloor.hq.app.claim.ClaimChanged;
import net.officefloor.hq.app.person.PersonChanged;
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
                + " date=" + e.date());
    }
}
