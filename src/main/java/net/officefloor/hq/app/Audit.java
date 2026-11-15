package net.officefloor.hq.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
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

    /**
     * The sheet's records exactly as the file holds them, newest first; with a name (not blank),
     * only those about that person: carrying {@code name=} or {@code person=} that name, compared
     * as names are (case and surrounding spaces ignored).
     */
    public synchronized List<String> lines(String sheetId, String name) {
        List<String> all = read();
        String who = name == null ? "" : name.trim();
        List<String> found = new ArrayList<>();
        for (String line : all) {
            if (hasField(line, "sheet", sheetId, false)
                    && (who.isEmpty() || hasField(line, "name", who, true) || hasField(line, "person", who, true))) {
                found.add(line);
            }
        }
        Collections.reverse(found);
        return found;
    }

    /**
     * The sheet's records about one claim exactly as the file holds them, newest first: with a
     * number, those carrying {@code number=} it; without, those naming its person, amount and date
     * together ({@code person=.. amount=.. date=..}) that carry no number of their own.
     */
    public synchronized List<String> claimLines(String sheetId, String number, String person, String amount,
            String date) {
        String about = person + " amount=" + amount + " date=" + date;
        List<String> found = new ArrayList<>();
        for (String line : read()) {
            if (!hasField(line, "sheet", sheetId, false)) {
                continue;
            }
            boolean match = number != null
                    ? hasField(line, "number", number, false)
                    : !line.contains(" number=") && hasField(line, "person", about, false);
            if (match) {
                found.add(line);
            }
        }
        Collections.reverse(found);
        return found;
    }

    /**
     * The sheet's activity: for every user ({@code by=}) and every event in the sheet's records,
     * one line {@code <user> <EVENT> <count>}, sorted by user then event.
     */
    public synchronized List<String> activity(String sheetId) {
        TreeMap<String, TreeMap<String, Integer>> counts = new TreeMap<>();
        for (String line : read()) {
            int space = line.indexOf(' ');
            String user = fieldValue(line, "by");
            if (space <= 0 || user == null || !hasField(line, "sheet", sheetId, false)) {
                continue;
            }
            counts.computeIfAbsent(user, u -> new TreeMap<>()).merge(line.substring(0, space), 1, Integer::sum);
        }
        List<String> found = new ArrayList<>();
        counts.forEach((user, events) -> events.forEach((event, n) -> found.add(user + " " + event + " " + n)));
        return found;
    }

    /** The value of the record's first {@code key=} field after the event name, up to the next
     *  space; null when it has none. */
    private static String fieldValue(String line, String key) {
        String field = " " + key + "=";
        int at = line.indexOf(field);
        if (at < 0) {
            return null;
        }
        int start = at + field.length();
        int end = line.indexOf(' ', start);
        return line.substring(start, end < 0 ? line.length() : end);
    }

    private List<String> read() {
        try {
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether the record carries the field {@code key=value}: after the event name, ending the
     *  line or followed by a space. */
    private static boolean hasField(String line, String key, String value, boolean ignoreCase) {
        String field = " " + key + "=";
        for (int at = line.indexOf(field); at >= 0; at = line.indexOf(field, at + 1)) {
            int start = at + field.length();
            int end = start + value.length();
            if (end <= line.length() && line.regionMatches(ignoreCase, start, value, 0, value.length())
                    && (end == line.length() || line.charAt(end) == ' ')) {
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
