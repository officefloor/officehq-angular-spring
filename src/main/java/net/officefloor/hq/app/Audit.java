package net.officefloor.hq.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.officefloor.hq.app.asof.AsOfContext;
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
 * unless it only reports figures (ROLLUP, TOTALS, ALERT). A SNAPSHOT, written by a change of today the user makes,
 * carries it too.
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

    /**
     * Append one audit record. Tests assert the exact line(s) in the file. A request answered as of a date changes
     * nothing, so it writes none.
     */
    public synchronized void record(String entry) {
        if (AsOfContext.active()) {
            return;
        }
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

    /** A field of a record: its key and value, the value running to the next field or the end. */
    private static final Pattern FIELD = Pattern.compile("(?<=^| )([a-z][a-z-]*)=(.*?)(?= [a-z][a-z-]*=|$)");

    /**
     * The records of a sheet exactly as the file holds them, newest first. With a name, only those about that person:
     * carrying name= or person= that name, compared as names are (case and surrounding spaces ignored).
     */
    public synchronized List<String> lines(String sheetId, String name) {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> all;
        try {
            all = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String key = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String line : all) {
            Matcher m = HEAD.matcher(line);
            if (m.find() && m.group(2).equals(sheetId) && (key.isEmpty() || about(line, key))) {
                found.add(line);
            }
        }
        Collections.reverse(found);
        return found;
    }

    /**
     * The records of a sheet about one claim, exactly as the file holds them, newest first: with a number, those
     * carrying number= that number; without one, those carrying no number but naming the person (compared as names
     * are), the amount and the date together.
     */
    public synchronized List<String> claimLines(String sheetId, String number, String person, String amount,
            String date) {
        String key = person.strip().toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String line : lines(sheetId, null)) {
            Map<String, String> f = fields(line);
            boolean about = number != null ? number.equals(f.get("number"))
                    : !f.containsKey("number") && f.containsKey("person")
                            && f.get("person").strip().toLowerCase(Locale.ROOT).equals(key)
                            && amount.equals(f.get("amount")) && date.equals(f.get("date"));
            if (about) {
                found.add(line);
            }
        }
        return found;
    }

    /** A record's fields by key; the first of a key repeated. */
    private static Map<String, String> fields(String line) {
        Map<String, String> found = new HashMap<>();
        Matcher f = FIELD.matcher(line);
        while (f.find()) {
            found.putIfAbsent(f.group(1), f.group(2));
        }
        return found;
    }

    /** Whether a record carries name= or person= the given name key. */
    private static boolean about(String line, String key) {
        Matcher f = FIELD.matcher(line);
        while (f.find()) {
            String k = f.group(1);
            if ((k.equals("name") || k.equals("person")) && f.group(2).strip().toLowerCase(Locale.ROOT).equals(key)) {
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
