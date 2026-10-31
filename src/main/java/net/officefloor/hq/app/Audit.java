package net.officefloor.hq.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.officefloor.hq.app.history.SheetHistory;
import net.officefloor.hq.app.role.Delegations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Audit sink: appends one record per line to a KNOWN FILE (app.audit.file, default .run/audit.log)
 * so tests can assert side-effects by reading that file — the second test-assertion channel
 * alongside the UI. The file path + one-record-per-line format is a stable contract, like
 * data-testid: features may be rewritten as long as they keep emitting the agreed records.
 *
 * Inject this bean into any feature that must record an audit entry. Each write flushes (append +
 * SYNC) so a test reads the record immediately.
 */
@Service
public class Audit {

    private final Path file;
    private final Delegations delegations;
    private final SheetHistory history;

    public Audit(@Value("${app.audit.file:.run/audit.log}") String path, Delegations delegations,
            SheetHistory history) {
        this.file = Path.of(path);
        this.delegations = delegations;
        this.history = history;
    }

    /** Append one audit record. Tests assert the exact line(s) in the file. */
    public synchronized void record(String entry) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, entry + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Append one audit record once the current transaction commits, so a change that is refused or
     * rolled back writes nothing. Outside a transaction the record is appended at once.
     */
    public void recordOnCommit(String entry) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            record(entry);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                record(entry);
            }
        });
    }

    /**
     * Append the record of a change the user made on the sheet once the current transaction commits, ending it with
     * {@code for=<steward>} while the user acts as a steward by that steward's delegation. A record that only reports
     * figures (a rollup, the totals, a notice) is appended with {@link #recordOnCommit} instead. What the sheet holds
     * once the change is made is kept in its history, under its today.
     */
    public void recordChangeOnCommit(String sheetId, String user, String entry) {
        history.keepOnCommit(sheetId);
        recordActingOnCommit(sheetId, user, entry);
    }

    /**
     * Append a record of what the user did on the sheet once the current transaction commits, ending it with
     * {@code for=<steward>} while the user acts as a steward by that steward's delegation (on the sheet's today as it
     * is when this is called), without keeping what the sheet holds in its history.
     */
    public void recordActingOnCommit(String sheetId, String user, String entry) {
        recordOnCommit(delegations.actingFor(sheetId, user).map(steward -> entry + " for=" + steward).orElse(entry));
    }

    /** Truncate the audit file. */
    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
