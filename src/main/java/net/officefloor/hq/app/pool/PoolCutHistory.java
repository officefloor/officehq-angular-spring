package net.officefloor.hq.app.pool;

import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import net.officefloor.hq.app.asof.SheetHistory;
import net.officefloor.hq.app.period.Period;
import net.officefloor.hq.app.period.PeriodRepository;
import net.officefloor.hq.app.period.PoolCutLine;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pools as they stood for each period that is closed or soft-closed, or whose to-date is on or before
 * the sheet's today. A closed or soft-closed period's cuts are those fixed when it was closed. For any
 * other, the pools as they stood at the end of it: the sheet is put back from its {@link SheetHistory} as it stood at the end of the to-date, with that
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

    /**
     * Each closed or soft-closed period with the cuts fixed when it was closed, and each other ended period
     * with the sheet's pools as they stood at the end of it, by from-date.
     */
    public List<PeriodPools> history(String sheetId) {
        LocalDate today = sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
        if (today == null || !history.exists(sheetId)) return List.of();
        List<Period> shown = periods.findBySheetIdOrderByFromDateAsc(sheetId).stream()
                .filter(p -> fixed(p) || !p.getToDate().isAfter(today)).toList();
        List<PeriodPools> out = new ArrayList<>();
        for (Period period : shown) {
            out.add(new PeriodPools(period.getName(),
                    fixed(period) ? fixedPools(period) : asOf(sheetId, period.getToDate())));
        }
        return out;
    }

    private static boolean fixed(Period period) {
        return period.isClosed() || period.isSoftClosed();
    }

    /** The pools, each with just its members' fixed cuts, as the period's close fixed them. */
    private static List<PoolService.PoolLine> fixedPools(Period period) {
        Map<List<Long>, List<PoolService.Member>> byPool = new LinkedHashMap<>();
        for (PoolCutLine line : period.getClosedPoolCuts()) {
            byPool.computeIfAbsent(Arrays.asList(line.getPoolId(), line.getCrewId()), k -> new ArrayList<>())
                    .add(new PoolService.Member(line.getPersonId(), line.getMemberPoolId(), null, null, false, false,
                            true, line.getCut()));
        }
        List<PoolService.PoolLine> out = new ArrayList<>();
        byPool.forEach((key, members) -> out.add(
                new PoolService.PoolLine(key.get(0), key.get(1), null, members, null, false)));
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
