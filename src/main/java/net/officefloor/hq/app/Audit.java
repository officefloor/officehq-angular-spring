package net.officefloor.hq.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** The sheet and user a record names, after its event name. */
    private static final Pattern WHO = Pattern.compile("^(\\S+) sheet=(\\S+) by=(\\S+)");

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
     * rolled back writes nothing. Outside a transaction the record is appended at once. A record of a change
     * made by a user acting as a steward by delegation (other than a ROLLUP or TOTALS line) ends with
     * {@code for=<the steward who delegated>}.
     */
    public void recordOnCommit(String record) {
        recordOnCommit(record, "");
    }

    /** As {@link #recordOnCommit(String)}, with {@code trailing} fields written after any {@code for=<steward>}. */
    public void recordOnCommit(String record, String trailing) {
        String entry = record + delegated(record) + trailing;
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

    /** " for=<steward>" when the record's user acts as a steward by delegation on the sheet's today; else "". */
    private String delegated(String entry) {
        Matcher who = WHO.matcher(entry);
        if (!who.find() || who.group(1).equals("ROLLUP") || who.group(1).equals("TOTALS")) {
            return "";
        }
        return settings.getObject().delegatorOf(who.group(2), who.group(3)).map(s -> " for=" + s).orElse("");
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
