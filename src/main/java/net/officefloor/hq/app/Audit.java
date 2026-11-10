package net.officefloor.hq.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.role.RoleService;
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

    /** The records that only report figures: never marked as made for a delegating steward. */
    private static final Set<String> FIGURES = Set.of("ROLLUP", "TOTALS", "ALERT");

    private final Path file;
    // looked up when used: the role service records its own changes here
    private final ObjectProvider<RoleService> roles;

    public Audit(@Value("${app.audit.file:.run/audit.log}") String path, ObjectProvider<RoleService> roles) {
        this.file = Path.of(path);
        this.roles = roles;
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
    public void recordOnCommit(String change) {
        String entry = change + delegated(change);
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
     * The ending of a change's record made by a user acting as a steward under a delegation in force
     * on the sheet's today: {@code for=<the steward who delegated>}; otherwise nothing.
     */
    private String delegated(String entry) {
        String[] fields = entry.split(" ");
        if (FIGURES.contains(fields[0])) {
            return "";
        }
        String sheet = field(fields, "sheet=");
        String by = field(fields, "by=");
        if (sheet == null || by == null) {
            return "";
        }
        return roles.getObject().delegatorOf(sheet, by).map(steward -> " for=" + steward).orElse("");
    }

    private static String field(String[] fields, String name) {
        for (String f : fields) {
            if (f.startsWith(name)) {
                return f.substring(name.length());
            }
        }
        return null;
    }

    /** Every record the file holds now, in the order they were appended; none when there is no file. */
    public synchronized List<String> lines() {
        try {
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
