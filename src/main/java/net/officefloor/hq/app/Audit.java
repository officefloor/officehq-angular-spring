package net.officefloor.hq.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * A change made by a user acting for the steward who delegated the role to it ends each of its
 * records with for=<that steward>, except the records that only report figures (see {@link #FIGURES}).
 */
@Service
public class Audit {

    /** The events whose records only report figures: they never carry who a user acted for. */
    private static final Set<String> FIGURES = Set.of("ROLLUP", "TOTALS", "ALERT");

    /** A record's event, sheet and user: every record starts "EVENT sheet=<id> by=<user>". */
    private static final Pattern HEAD = Pattern.compile("^(\\S+) sheet=(.*?) by=(.*?)(?= [a-z][a-z-]*=|$)");

    private final Path file;
    private final ObjectProvider<ActingFor> actingFor;

    public Audit(@Value("${app.audit.file:.run/audit.log}") String path, ObjectProvider<ActingFor> actingFor) {
        this.file = Path.of(path);
        this.actingFor = actingFor;
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
    public void recordOnCommit(String record) {
        String entry = record + forWhom(record);
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

    /** " for=<steward>" when the record's user acts for a steward on the sheet's today, else nothing. */
    private String forWhom(String record) {
        Matcher head = HEAD.matcher(record);
        if (!head.find() || FIGURES.contains(head.group(1))) return "";
        ActingFor delegations = actingFor.getIfAvailable();
        if (delegations == null) return "";
        return delegations.actingFor(head.group(2), head.group(3)).map((steward) -> " for=" + steward).orElse("");
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
