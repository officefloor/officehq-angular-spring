package net.officefloor.hq.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.beans.factory.ObjectProvider;
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

    /** The events that only report figures: they never name a delegating steward. */
    private static final Set<String> FIGURES_ONLY = Set.of("ROLLUP", "TOTALS", "ALERT", "SNAPSHOT");

    private final Path file;
    private final ObjectProvider<SettingService> settings;

    public Audit(@Value("${app.audit.file:.run/audit.log}") String path, ObjectProvider<SettingService> settings) {
        this.file = Path.of(path);
        this.settings = settings;
    }

    /**
     * The record as written: a change made by a user acting as a steward by a delegation in force on
     * the sheet's today ends with for=<the steward who delegated>; a record that only reports figures
     * does not.
     */
    private String delegated(String entry) {
        String event = entry.split(" ", 2)[0];
        int sheetAt = entry.indexOf(" sheet=");
        int byAt = entry.indexOf(" by=");
        if (FIGURES_ONLY.contains(event) || sheetAt < 0 || byAt < sheetAt) {
            return entry;
        }
        String sheetId = entry.substring(sheetAt + " sheet=".length(), byAt);
        int byEnd = entry.indexOf(' ', byAt + " by=".length());
        String by = entry.substring(byAt + " by=".length(), byEnd < 0 ? entry.length() : byEnd);
        return settings.getObject().delegatedBy(sheetId, by).map(steward -> entry + " for=" + steward).orElse(entry);
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
     * rolled back writes nothing; outside a transaction it is appended at once.
     */
    public void recordOnCommit(String change) {
        String entry = delegated(change);
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

    /** Truncate the audit file. */
    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
