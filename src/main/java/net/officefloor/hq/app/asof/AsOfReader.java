package net.officefloor.hq.app.asof;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads a sheet as it stood at the end of a date: inside a transaction that is rolled back, the sheet's rows are put
 * back as of the date (the date its today) and the read runs against them. One as-of read of a sheet at a time.
 */
@Component
public class AsOfReader {

    private final SheetSnapshotService snapshots;
    private final TransactionTemplate rolledBack;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public AsOfReader(SheetSnapshotService snapshots, PlatformTransactionManager transactions) {
        this.snapshots = snapshots;
        this.rolledBack = new TransactionTemplate(transactions);
    }

    /** What the read gives with the sheet as it stood at the end of the date; nothing it changes is kept. */
    public <T> T read(String sheetId, LocalDate asOf, Supplier<T> read) {
        ReentrantLock lock = locks.computeIfAbsent(sheetId, k -> new ReentrantLock());
        lock.lock();
        try {
            return rolledBack.execute(status -> {
                status.setRollbackOnly();
                snapshots.restore(sheetId, asOf);
                AsOfContext.set(asOf);
                try {
                    return read.get();
                } finally {
                    AsOfContext.clear();
                }
            });
        } finally {
            lock.unlock();
        }
    }
}
