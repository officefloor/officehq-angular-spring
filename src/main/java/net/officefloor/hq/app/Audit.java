package net.officefloor.hq.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.officefloor.hq.app.role.DelegationService;
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

    /** The records that only report figures: they never say on whose behalf the change was made. */
    private static final Set<String> FIGURES_ONLY = Set.of("ROLLUP", "TOTALS", "ALERT");

    private static final Pattern SHEET_BY = Pattern.compile("^(\\S+) sheet=(\\S+) by=(\\S+)");

    /** A name a record is about: the value of its {@code name=} or {@code person=} field, up to the next field. */
    private static final Pattern ABOUT = Pattern.compile(" (?:name|person)=(.*?)(?= [\\w-]+=|$)");

    private final Path file;
    private final ObjectProvider<DelegationService> delegations;

    public Audit(@Value("${app.audit.file:.run/audit.log}") String path,
            ObjectProvider<DelegationService> delegations) {
        this.file = Path.of(path);
        this.delegations = delegations;
    }

    /**
     * The record as written: a change made by a user acting as a steward by delegation on the sheet's today ends with
     * {@code for=<the steward who delegated>}; a record that only reports figures is left as it is.
     */
    private String onBehalf(String entry) {
        Matcher m = SHEET_BY.matcher(entry);
        if (!m.find() || FIGURES_ONLY.contains(m.group(1))) {
            return entry;
        }
        return delegations.getObject().delegatorOf(m.group(2), m.group(3)).map(s -> entry + " for=" + s).orElse(entry);
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
        String entry = onBehalf(record);
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
     * The sheet's records exactly as the file holds them, newest first. With a name (not blank), only the records
     * about that person: those carrying {@code name=} or {@code person=} that name, compared as names are compared
     * (case and surrounding spaces ignored).
     */
    public synchronized List<String> lines(String sheetId, String name) {
        String who = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        List<String> all;
        try {
            all = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> lines = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            String line = all.get(i);
            Matcher m = SHEET_BY.matcher(line);
            if (m.find() && m.group(2).equals(sheetId) && (who.isEmpty() || isAbout(line, who))) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static boolean isAbout(String line, String who) {
        Matcher m = ABOUT.matcher(line);
        while (m.find()) {
            if (m.group(1).trim().toLowerCase(Locale.ROOT).equals(who)) {
                return true;
            }
        }
        return false;
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
