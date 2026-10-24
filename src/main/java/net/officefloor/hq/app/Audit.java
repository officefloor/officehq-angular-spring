package net.officefloor.hq.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.officefloor.hq.app.settings.DelegationService;
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

    /** The lines that only report figures: they never say whom a change was made for. */
    private static final Set<String> FIGURES = Set.of("ROLLUP", "TOTALS", "ALERT");

    private final Path file;
    private final ObjectProvider<DelegationService> delegations;

    public Audit(@Value("${app.audit.file:.run/audit.log}") String path, ObjectProvider<DelegationService> delegations) {
        this.file = Path.of(path);
        this.delegations = delegations;
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
     * Append one record for a change to a sheet: the event, then {@code sheet=} and {@code by=},
     * then the given fields, separated by single spaces; a change by a user acting as a steward by a
     * delegation ends with {@code for=} the steward who delegated it (not a line that only reports
     * figures). Inside a transaction the record is written
     * only once it commits, so a change that is refused or rolled back writes nothing.
     */
    public void change(String event, String sheetId, String user, String... fields) {
        String forSteward = FIGURES.contains(event) ? ""
                : delegations.getObject().actingFor(sheetId, user).map(steward -> " for=" + steward).orElse("");
        String entry = event + " sheet=" + sheetId + " by=" + user
                + (fields.length == 0 ? "" : " " + String.join(" ", fields)) + forSteward;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    record(entry);
                }
            });
        } else {
            record(entry);
        }
    }

    /**
     * The lines the file holds for a sheet, exactly as written, newest first. With a name (not blank),
     * only the lines about that person: those with a {@code name=} or {@code person=} field equal to
     * it, compared as names are (case and surrounding spaces ignored).
     */
    public synchronized List<String> lines(String sheetId, String name) {
        List<String> all;
        try {
            all = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String key = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            String line = all.get(i);
            if (ofSheet(line, sheetId) && (key.isEmpty() || isAbout(line, key))) found.add(line);
        }
        return found;
    }

    /**
     * The lines the file holds for a sheet about one claim, exactly as written, newest first: with a
     * {@code number}, those whose {@code number=} field is it; without one, those carrying no number
     * whose {@code person=} (compared as names are), {@code amount=} and {@code date=} fields are the
     * claim's.
     */
    public synchronized List<String> claimLines(String sheetId, String number, String person, String amount,
            String date) {
        String key = person.trim().toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String line : lines(sheetId, "")) {
            Map<String, String> fields = fieldsOf(line);
            boolean about = number != null
                    ? number.equals(fields.get("number"))
                    : !fields.containsKey("number")
                            && key.equals(fields.getOrDefault("person", "").trim().toLowerCase(Locale.ROOT))
                            && amount.equals(fields.get("amount")) && date.equals(fields.get("date"));
            if (about) found.add(line);
        }
        return found;
    }

    /** A line's fields by name, each the first of that name (a value runs to the next field). */
    private static Map<String, String> fieldsOf(String line) {
        Map<String, String> fields = new HashMap<>();
        String field = null;
        StringBuilder value = new StringBuilder();
        for (String token : (line + " end=").split(" ")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                if (field != null) fields.putIfAbsent(field, value.toString());
                field = token.substring(0, eq);
                value.setLength(0);
                value.append(token.substring(eq + 1));
            } else if (field != null) {
                value.append(' ').append(token);
            }
        }
        return fields;
    }

    private static boolean ofSheet(String line, String sheetId) {
        String[] tokens = line.split(" ", 3);
        return tokens.length >= 2 && tokens[1].equals("sheet=" + sheetId);
    }

    /** Whether a line has a name= or person= field naming the person (a value runs to the next field). */
    private static boolean isAbout(String line, String key) {
        String field = null;
        StringBuilder value = new StringBuilder();
        for (String token : (line + " end=").split(" ")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                if (field != null && (field.equals("name") || field.equals("person"))
                        && value.toString().trim().toLowerCase(Locale.ROOT).equals(key)) {
                    return true;
                }
                field = token.substring(0, eq);
                value.setLength(0);
                value.append(token.substring(eq + 1));
            } else if (field != null) {
                value.append(' ').append(token);
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
