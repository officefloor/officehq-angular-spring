package net.officefloor.hq.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
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
 * A record of a change made by a user acting on behalf of another (see {@link OnBehalf}) ends with for=<that one>,
 * unless it only reports figures (ROLLUP, TOTALS, ALERT).
 */
@Service
public class Audit {

    /** Tells for whom a user acts on a sheet, when they act on another's behalf. */
    public interface OnBehalf {
        Optional<String> forOf(String sheetId, String user);
    }

    /** The records that only report figures, which never carry for=. */
    private static final Set<String> FIGURES_ONLY = Set.of("ROLLUP", "TOTALS", "ALERT");

    /** A record's event, sheet and user: its head. */
    private static final Pattern HEAD = Pattern.compile("^(\\S+) sheet=(\\S+) by=(.+?)(?= [a-z][a-z-]*=|$)");

    private final Path file;
    private final ObjectProvider<OnBehalf> onBehalf;

    public Audit(@Value("${app.audit.file:.run/audit.log}") String path, ObjectProvider<OnBehalf> onBehalf) {
        this.file = Path.of(path);
        this.onBehalf = onBehalf;
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
     * Append one audit record once the current transaction commits, so a change that is refused (and
     * rolled back) writes nothing. Outside a transaction the record is appended at once.
     */
    public void recordOnCommit(String record) {
        String entry = record + onBehalfOf(record);
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

    /** " for=<who>" when the record's user makes the change on another's behalf, as things stand now; else "". */
    private String onBehalfOf(String entry) {
        Matcher m = HEAD.matcher(entry);
        OnBehalf who = onBehalf.getIfAvailable();
        if (who == null || !m.find() || FIGURES_ONLY.contains(m.group(1))) {
            return "";
        }
        return who.forOf(m.group(2), m.group(3)).map(f -> " for=" + f).orElse("");
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
