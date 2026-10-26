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
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.officefloor.hq.app.person.Person;
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

    /** A record's name= and person= fields: each value runs up to the next field or the end. */
    private static final Pattern ABOUT = Pattern.compile(" (?:name|person)=(.*?)(?= [a-z][a-z-]*=|$)");

    /**
     * The sheet's records exactly as the file holds them, newest first. With a name, only the
     * records carrying name=<the name> or person=<the name>, compared as names are compared.
     */
    public synchronized List<String> lines(String sheetId, String name) {
        List<String> all;
        try {
            all = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String key = name == null || name.isBlank() ? null : Person.keyOf(name);
        List<String> found = new ArrayList<>();
        for (String line : all) {
            Matcher head = HEAD.matcher(line);
            if (!head.find() || !head.group(2).equals(sheetId)) continue;
            if (key != null && !isAbout(line.substring(head.end()), key)) continue;
            found.add(line);
        }
        Collections.reverse(found);
        return found;
    }

    private static boolean isAbout(String fields, String key) {
        Matcher about = ABOUT.matcher(fields);
        while (about.find()) {
            if (Person.keyOf(about.group(1)).equals(key)) return true;
        }
        return false;
    }

    /** A record's field: its value runs up to the next field or the end. */
    private static final Pattern FIELD = Pattern.compile(" ([a-z][a-z-]*)=(.*?)(?= [a-z][a-z-]*=|$)");

    /**
     * The sheet's records about one claim exactly as the file holds them, newest first: with a number, those
     * carrying number=<the number>; without, those carrying no number that name its person (compared as names
     * are compared), amount and date together.
     */
    public List<String> claimLines(String sheetId, String number, String person, String amount, String date) {
        List<String> found = new ArrayList<>();
        for (String line : lines(sheetId, null)) {
            Matcher head = HEAD.matcher(line);
            if (!head.find()) continue;
            Map<String, String> fields = new HashMap<>();
            Matcher field = FIELD.matcher(line.substring(head.end()));
            while (field.find()) fields.putIfAbsent(field.group(1), field.group(2));
            boolean about = number != null
                    ? number.equals(fields.get("number"))
                    : !fields.containsKey("number") && fields.containsKey("person")
                            && Person.keyOf(fields.get("person")).equals(Person.keyOf(person))
                            && amount.equals(fields.get("amount")) && date.equals(fields.get("date"));
            if (about) found.add(line);
        }
        return found;
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
