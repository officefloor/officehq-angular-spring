package net.officefloor.hq.app;

import java.math.RoundingMode;
import net.officefloor.hq.app.access.AccessService;
import net.officefloor.hq.app.access.Delegated;
import net.officefloor.hq.app.access.RoleSet;
import net.officefloor.hq.app.access.SettingChanged;
import net.officefloor.hq.app.claim.ClaimChanged;
import net.officefloor.hq.app.claim.ClaimDeferred;
import net.officefloor.hq.app.claim.ClaimProofAdded;
import net.officefloor.hq.app.claim.ClaimReassigned;
import net.officefloor.hq.app.claim.ClaimSplit;
import net.officefloor.hq.app.claim.ClaimResettled;
import net.officefloor.hq.app.period.PeriodAdded;
import net.officefloor.hq.app.period.PeriodClosed;
import net.officefloor.hq.app.period.PeriodReopened;
import net.officefloor.hq.app.person.PatronAdded;
import net.officefloor.hq.app.person.PersonChanged;
import net.officefloor.hq.app.person.PersonHeld;
import net.officefloor.hq.app.person.PersonRetired;
import net.officefloor.hq.app.person.PersonWatched;
import net.officefloor.hq.app.person.WatcherNotified;
import net.officefloor.hq.app.rate.RateChanged;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.rollup.RollupChanged;
import net.officefloor.hq.app.sheet.SheetArchived;
import net.officefloor.hq.app.statement.StatementTaken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Writes one audit line per change to a sheet, once the change is committed: a refused change
 * rolls back and writes nothing. Each line is the event, {@code sheet=} and {@code by=}, then the
 * event's fields; a change by a user acting in a delegated steward role then ends with {@code for=}
 * the steward who delegated it (not the ROLLUP and TOTALS lines, nor NOTIFY, which only report).
 */
@Component
public class SheetAudit {

    private final Audit audit;
    private final AccessService access;

    public SheetAudit(Audit audit, AccessService access) {
        this.audit = audit;
        this.access = access;
    }

    /**
     * Record the line of a change the user made: while the user acts in the steward role delegated to
     * them, it ends with {@code for=} the steward who delegated it.
     */
    private void change(String sheetId, String user, String line) {
        String delegator = access.delegator(sheetId, user);
        audit.record(delegator == null ? line : line + " for=" + delegator);
    }

    @TransactionalEventListener
    public void on(PersonChanged e) {
        change(e.sheetId(), e.user(), e.event() + " sheet=" + e.sheetId() + " by=" + e.user()
                + " name=" + e.name() + " grade=" + e.grade() + (e.terms() == null ? "" : " terms=" + e.terms()));
    }

    @TransactionalEventListener
    public void on(PersonRetired e) {
        change(e.sheetId(), e.user(), "PERSON_RETIRED sheet=" + e.sheetId() + " by=" + e.user()
                + " name=" + e.name() + " from=" + e.from());
    }

    @TransactionalEventListener
    public void on(PersonHeld e) {
        change(e.sheetId(), e.user(), "PERSON_HELD sheet=" + e.sheetId() + " by=" + e.user() + " name=" + e.name()
                + " from=" + e.from());
    }

    @TransactionalEventListener
    public void on(PersonWatched e) {
        change(e.sheetId(), e.user(), e.event() + " sheet=" + e.sheetId() + " by=" + e.user() + " name=" + e.name());
    }

    @TransactionalEventListener
    public void on(WatcherNotified e) {
        audit.record("NOTIFY sheet=" + e.sheetId() + " by=" + e.user() + " user=" + e.watcher()
                + " person=" + e.person() + " number=" + e.number());
    }

    @TransactionalEventListener
    public void on(PatronAdded e) {
        change(e.sheetId(), e.user(), "PATRON_ADDED sheet=" + e.sheetId() + " by=" + e.user()
                + " name=" + e.name() + " patron=" + e.patron());
    }

    @TransactionalEventListener
    public void on(ClaimChanged e) {
        change(e.sheetId(), e.user(), e.event() + " sheet=" + e.sheetId() + " by=" + e.user()
                + " person=" + e.person() + " amount=" + e.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " date=" + e.date() + (e.number() == null ? "" : " number=" + e.number())
                + (e.due() == null ? "" : " due=" + e.due())
                + (e.reason() == null ? "" : " reason=" + e.reason())
                + (e.reversed() == null ? "" : " reversed=" + e.reversed()));
    }

    @TransactionalEventListener
    public void on(ClaimReassigned e) {
        change(e.sheetId(), e.user(), "CLAIM_REASSIGNED sheet=" + e.sheetId() + " by=" + e.user()
                + " person=" + e.person() + " amount=" + e.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " date=" + e.date() + (e.number() == null ? "" : " number=" + e.number())
                + " from=" + e.from() + " to=" + e.to());
    }

    @TransactionalEventListener
    public void on(ClaimSplit e) {
        change(e.sheetId(), e.user(), "CLAIM_SPLIT sheet=" + e.sheetId() + " by=" + e.user()
                + " person=" + e.person() + " amount=" + e.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " date=" + e.date() + " number=" + e.number() + " into=" + e.into());
    }

    @TransactionalEventListener
    public void on(ClaimDeferred e) {
        change(e.sheetId(), e.user(), "CLAIM_DEFERRED sheet=" + e.sheetId() + " by=" + e.user()
                + " person=" + e.person() + " amount=" + e.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " date=" + e.date() + (e.number() == null ? "" : " number=" + e.number())
                + " until=" + e.until());
    }

    @TransactionalEventListener
    public void on(ClaimProofAdded e) {
        change(e.sheetId(), e.user(), "CLAIM_PROOF_ADDED sheet=" + e.sheetId() + " by=" + e.user()
                + " person=" + e.person() + " amount=" + e.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " date=" + e.date() + (e.number() == null ? "" : " number=" + e.number())
                + " proofs=" + e.proofs());
    }

    @TransactionalEventListener
    public void on(ClaimResettled e) {
        change(e.sheetId(), e.user(), "CLAIM_RESETTLED sheet=" + e.sheetId() + " by=" + e.user()
                + " person=" + e.person() + " amount=" + e.amount().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " date=" + e.date() + (e.number() == null ? "" : " number=" + e.number())
                + " old=" + figure(e.old()) + " new=" + figure(e.fresh()));
    }

    private static String figure(java.math.BigDecimal due) {
        return due == null ? "n/a" : due.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @TransactionalEventListener
    public void on(RateChanged e) {
        change(e.sheetId(), e.user(), "RATE_SET sheet=" + e.sheetId() + " by=" + e.user()
                + " grade=" + e.grade() + " rate=" + e.rate().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + (e.from() == null ? "" : " from=" + e.from())
                + (e.to() == null ? "" : " to=" + e.to()));
    }

    @TransactionalEventListener
    public void on(SheetArchived e) {
        change(e.sheetId(), e.user(), "SHEET_ARCHIVED sheet=" + e.sheetId() + " by=" + e.user());
    }

    @TransactionalEventListener
    public void on(RoleSet e) {
        change(e.sheetId(), e.user(), "ROLE_SET sheet=" + e.sheetId() + " by=" + e.user() + " user=" + e.name() + " role=" + e.role());
    }

    @TransactionalEventListener
    public void on(SettingChanged e) {
        change(e.sheetId(), e.user(), "SETTING_CHANGED sheet=" + e.sheetId() + " by=" + e.user() + " key=" + e.key() + " value=" + e.value()
                + (e.from() == null ? "" : " from=" + e.from()));
    }

    @TransactionalEventListener
    public void on(PeriodAdded e) {
        change(e.sheetId(), e.user(), "PERIOD_ADDED sheet=" + e.sheetId() + " by=" + e.user()
                + " period=" + e.name() + " from=" + e.from() + " to=" + e.to());
    }

    @TransactionalEventListener
    public void on(PeriodClosed e) {
        change(e.sheetId(), e.user(), "PERIOD_CLOSED sheet=" + e.sheetId() + " by=" + e.user()
                + " period=" + e.name() + " total=" + e.total().setScale(2, RoundingMode.HALF_UP).toPlainString()
                + (e.auto() ? " auto=yes" : ""));
    }

    @TransactionalEventListener
    public void on(PeriodReopened e) {
        change(e.sheetId(), e.user(), "PERIOD_REOPENED sheet=" + e.sheetId() + " by=" + e.user() + " period=" + e.name());
    }

    @TransactionalEventListener
    public void on(StatementTaken e) {
        change(e.sheetId(), e.user(), "STATEMENT sheet=" + e.sheetId() + " by=" + e.user() + " name=" + e.name()
                + " due-total=" + (e.dueTotal() == null ? "n/a"
                        : e.dueTotal().setScale(2, RoundingMode.HALF_UP).toPlainString())
                + " claims=" + e.claims());
    }

    @TransactionalEventListener
    public void on(Delegated e) {
        change(e.sheetId(), e.user(), "DELEGATED sheet=" + e.sheetId() + " by=" + e.user() + " user=" + e.name()
                + " from=" + e.from() + " to=" + e.to());
    }

    @TransactionalEventListener
    public void on(RollupChanged e) {
        for (Rollup.PersonFigures p : e.people()) {
            audit.record("ROLLUP sheet=" + e.sheetId() + " by=" + e.user() + " person=" + p.name()
                    + " claimed=" + p.claimed() + " due-total=" + p.dueTotal() + " overdue-total=" + p.overdueTotal());
        }
        if (e.totals() != null) {
            audit.record("TOTALS sheet=" + e.sheetId() + " by=" + e.user()
                    + " claimed-total=" + e.totals().claimed() + " due-total=" + e.totals().dueTotal()
                    + " overdue-total=" + e.totals().overdueTotal());
        }
    }
}
