package net.officefloor.hq.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
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

    /** The request attribute naming the steward a user delegated the steward role acts for: each line of a
     *  change they make ends with for=<steward>, but for the lines that only report figures. */
    public static final String ACTING_FOR = Audit.class.getName() + ".actingFor";

    /** The events that only report figures, and so carry no for=. */
    private static final String[] FIGURES = { "ROLLUP ", "TOTALS ", "ALERT " };

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
     * Append one audit record once the current transaction commits, so a change that is refused or
     * rolled back (by any rule) writes nothing. Outside a transaction the record is written now.
     */
    public void recordOnCommit(String line) {
        String entry = line + actingFor(line);
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

    private static String actingFor(String entry) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        Object steward = request == null ? null : request.getAttribute(ACTING_FOR, RequestAttributes.SCOPE_REQUEST);
        if (steward == null) {
            return "";
        }
        for (String figures : FIGURES) {
            if (entry.startsWith(figures)) {
                return "";
            }
        }
        return " for=" + steward;
    }

    /** Every record the file holds now, in the order written (none when there is no file yet). */
    public synchronized List<String> lines() {
        try {
            if (!Files.exists(file)) {
                return List.of();
            }
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isEmpty()).toList();
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
