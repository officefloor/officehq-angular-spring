package net.officefloor.hq.app.asof;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The app's transaction manager: as {@link JpaTransactionManager}, and just before a transaction
 * made by a request that changes a sheet commits, it keeps what the sheet then holds in the sheet's
 * history (see {@link SheetHistory}), in the same transaction, so what is kept is exactly what
 * commits.
 */
public class HistoryTransactionManager extends JpaTransactionManager {

    private final transient ObjectProvider<SheetHistory> history;

    public HistoryTransactionManager(EntityManagerFactory entityManagerFactory, ObjectProvider<SheetHistory> history) {
        super(entityManagerFactory);
        this.history = history;
    }

    @Override
    protected void prepareForCommit(DefaultTransactionStatus status) {
        super.prepareForCommit(status);
        if (!status.isNewTransaction() || status.isReadOnly()) return;
        SheetHistory.changingSheet().ifPresent((sheet) -> {
            EntityManagerHolder holder = (EntityManagerHolder) TransactionSynchronizationManager
                    .getResource(obtainEntityManagerFactory());
            if (holder != null) holder.getEntityManager().flush();
            history.getObject().keep(sheet);
        });
    }
}
