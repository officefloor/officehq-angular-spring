package net.officefloor.hq.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

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

    /** The request attribute naming the steward whose delegation the request's user acts under;
     *  every record of the change it makes then ends with {@code for=} that steward. */
    public static final String FOR = Audit.class.getName() + ".for";

    /** Records that only report figures, which never carry {@code for=}. A SNAPSHOT is the
     *  record of the today change its user made, so it carries it. */
    private static final Set<String> FIGURES = Set.of("ROLLUP", "TOTALS", "ALERT");

    private final Path file;

    public Audit(@Value("${app.audit.file:.run/audit.log}") String path) {
        this.file = Path.of(path);
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
     * Append one audit record once the current transaction commits, so a change that is refused
     * or rolled back writes nothing. Outside a transaction the record is appended at once.
     */
    public void recordOnCommit(String record) {
        String entry = withFor(record);
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

    /** The record ending with {@code for=} the steward the request's user acts for, if it does,
     *  unless it only reports figures. */
    private static String withFor(String entry) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        Object steward = request == null ? null : request.getAttribute(FOR, RequestAttributes.SCOPE_REQUEST);
        if (steward == null || FIGURES.contains(entry.substring(0, Math.max(entry.indexOf(' '), 0)))) {
            return entry;
        }
        return entry + " for=" + steward;
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
