package net.officefloor.hq.app.pool;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import net.officefloor.hq.app.asof.SheetHistory;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pools as they stood at the end of each period whose to-date is on or before the sheet's today:
 * the sheet is put back from its {@link SheetHistory} as it stood at the end of the to-date, with that
 * date as its today, in a transaction of its own (a savepoint, within an as-of read's transaction) that
 * is rolled back once its pools are read, so it changes nothing.
 */
@Service
public class PoolCutHistory {

    /** A period's name and the pools as they stood at the end of it. */
    public record PeriodPools(String period, List<PoolService.PoolLine> pools) {}

    private final SheetRepository sheets;
    private final PeriodRepository periods;
    private final PoolService service;
    private final SheetHistory history;
    private final EntityManager entities;
    private final DataSource dataSource;
    private final TransactionTemplate transaction;

    public PoolCutHistory(SheetRepository sheets, PeriodRepository periods, PoolService service, SheetHistory history,
            EntityManager entities, DataSource dataSource, PlatformTransactionManager transactions) {
        this.sheets = sheets;
        this.periods = periods;
        this.service = service;
        this.history = history;
        this.entities = entities;
        this.dataSource = dataSource;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Each ended period, by from-date, with the sheet's pools as they stood at the end of it. */
    public List<PeriodPools> history(String sheetId) {
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
        if (today == null || !history.exists(sheetId)) return List.of();
        List<Period> ended = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> !p.getToDate().isAfter(today)).toList();
        List<PeriodPools> out = new ArrayList<>();
        for (Period period : ended) out.add(new PeriodPools(period.getName(), asOf(sheetId, period.getToDate())));
        return out;
    }

    private List<PoolService.PoolLine> asOf(String sheetId, LocalDate date) {
        ReentrantLock lock = history.lock(sheetId);
        lock.lock();
        try {
            if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                return transaction.execute(status -> {
                    status.setRollbackOnly();
                    return restoredPools(sheetId, date);
                });
            }
            // within an as-of read: back to where it stood once read
            Connection con = DataSourceUtils.getConnection(dataSource);
            Savepoint savepoint = con.setSavepoint();
            try {
                return restoredPools(sheetId, date);
            } finally {
                con.rollback(savepoint);
                con.releaseSavepoint(savepoint);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            entities.clear();
            lock.unlock();
        }
    }

    private List<PoolService.PoolLine> restoredPools(String sheetId, LocalDate date) {
        entities.clear();
        history.restore(sheetId, date);
        entities.clear();
        return service.pools(sheetId);
    }
}
