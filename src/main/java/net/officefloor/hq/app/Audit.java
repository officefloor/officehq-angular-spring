package net.officefloor.hq.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
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

    /** The request attribute holding a {@code Supplier<String>} of the steward the requesting user
     *  acts for by delegation (null when none, as of the sheet's today when asked): each record of
     *  the change, but those that only report figures, then ends with {@code for=<steward>}. */
    public static final String ACTING_FOR = Audit.class.getName() + ".actingFor";

    /** The records that only report figures, never marked with who a change was made for. */
    private static final Set<String> FIGURES_ONLY = Set.of("ROLLUP", "TOTALS", "ALERT");

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
     * rolled back writes nothing. Outside a transaction the record is appended at once.
     */
    public void recordOnCommit(String line) {
        String entry = withActingFor(line);
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

    /** The record ending with who the change was made for, when the requesting user acts for a
     *  steward by delegation and the record is not one that only reports figures. */
    private static String withActingFor(String entry) {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null || FIGURES_ONLY.contains(entry.split(" ", 2)[0])) {
            return entry;
        }
        if (request.getAttribute(ACTING_FOR, RequestAttributes.SCOPE_REQUEST) instanceof Supplier<?> actingFor
                && actingFor.get() instanceof String steward) {
            return entry + " for=" + steward;
        }
        return entry;
    }

    /** Every record the file holds now, oldest first (none when there is no file yet). */
    public synchronized List<String> lines() {
        try {
            return Files.exists(file)
                    ? Files.readAllLines(file, StandardCharsets.UTF_8).stream().filter(l -> !l.isEmpty()).toList()
                    : List.of();
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
