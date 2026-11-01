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
 *
 * A change made by a user acting as a steward through a delegation has {@code for=<the steward who
 * delegated>} at the end of each of its lines, but for those that only report figures.
 */
@Service
public class Audit {

    /** The events that only report figures: never marked as made for a delegator. */
    private static final Set<String> FIGURES_ONLY = Set.of("ROLLUP", "TOTALS", "ALERT");

    private final Path file;
    private final ObjectProvider<SettingService> settings;

    public Audit(@Value("${app.audit.file:.run/audit.log}") String path, ObjectProvider<SettingService> settings) {
        this.file = Path.of(path);
        this.settings = settings;
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
    public void recordOnCommit(String line) {
        String entry = line + forDelegator(line);
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
     * " for=<steward>" when the line, of the form {@code EVENT sheet=<id> by=<user> ...}, is of a change
     * its user makes acting for a steward who delegated to them; else "".
     */
    private String forDelegator(String line) {
        String[] fields = line.split(" ", 4);
        if (fields.length < 3 || FIGURES_ONLY.contains(fields[0])) return "";
        if (!fields[1].startsWith("sheet=") || !fields[2].startsWith("by=")) return "";
        String sheetId = fields[1].substring("sheet=".length());
        String user = fields[2].substring("by=".length());
        if (sheetId.isEmpty() || user.isEmpty()) return "";
        String delegator = settings.getObject().actingFor(sheetId, user);
        return delegator == null ? "" : " for=" + delegator;
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
