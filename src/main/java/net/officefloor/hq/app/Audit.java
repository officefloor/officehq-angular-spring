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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import net.officefloor.hq.app.person.Person;
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

    /**
     * The request attribute naming the steward the signed-in user acts for, under a delegation in
     * force on the sheet's today: each record of the change then ends with for=&lt;steward&gt;.
     */
    public static final String ACTING_FOR = Audit.class.getName() + ".actingFor";

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
     * rolled back writes nothing. Records outside a transaction are appended at once.
     */
    public void recordOnCommit(String entry) {
        recordAfterCommit(entry + actingFor());
    }

    /**
     * Append a record of the figures a change moved (ROLLUP and TOTALS) once the current transaction
     * commits; unlike the change's own records it never names a steward acted for.
     */
    public void recordFiguresOnCommit(String entry) {
        recordAfterCommit(entry);
    }

    /** " for=<steward>" when the signed-in user acts for a steward in this request, else empty. */
    private static String actingFor() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        Object steward = request == null ? null : request.getAttribute(ACTING_FOR, RequestAttributes.SCOPE_REQUEST);
        return steward instanceof String name ? " for=" + name : "";
    }

    private void recordAfterCommit(String entry) {
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

    /** A field naming a person: name=&lt;value&gt; or person=&lt;value&gt;, the value running to the next field. */
    private static final java.util.regex.Pattern PERSON_FIELD =
            java.util.regex.Pattern.compile(" (?:name|person)=(.*?)(?= [^ =]+=|$)");

    /**
     * The sheet's lines exactly as the file holds them, newest first. With a name (not blank), only
     * the lines carrying name=&lt;it&gt; or person=&lt;it&gt;, compared as names are compared.
     */
    public List<String> linesOf(String sheetId, String name) {
        return name == null || name.isBlank() ? linesAbout(sheetId, null) : linesAbout(sheetId, Set.of(Person.keyOf(name)));
    }

    /**
     * The sheet's lines exactly as the file holds them, newest first. With keys (not null), only the
     * lines carrying name=&lt;one&gt; or person=&lt;one&gt;, compared as names are compared.
     */
    public synchronized List<String> linesAbout(String sheetId, Set<String> keys) {
        List<String> lines;
        try {
            lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String sheetField = " sheet=" + sheetId + " ";
        List<String> shown = new ArrayList<>();
        for (String line : lines) {
            if (line.contains(sheetField) && (keys == null || isAbout(line, keys))) {
                shown.add(line);
            }
        }
        Collections.reverse(shown);
        return shown;
    }

    /**
     * A claim's timeline: the sheet's lines exactly as the file holds them, newest first. With a number
     * (not null), the lines carrying number=&lt;it&gt;; without, the lines carrying no number that name
     * the person (compared as names are compared), the amount and the date together.
     */
    public List<String> linesOfClaim(String sheetId, String number, String person, String amount, String date) {
        if (number != null) {
            return linesAbout(sheetId, null).stream().filter((line) -> hasField(line, "number", number)).toList();
        }
        Set<String> keys = Set.of(Person.keyOf(person));
        return linesAbout(sheetId, keys).stream()
                .filter((line) -> !line.contains(" number=") && hasField(line, "amount", amount)
                        && hasField(line, "date", date))
                .toList();
    }

    /** Whether the line carries the field with exactly the value, as a whole space-separated token. */
    private static boolean hasField(String line, String field, String value) {
        String token = " " + field + "=" + value;
        int at = line.indexOf(token);
        while (at >= 0) {
            int end = at + token.length();
            if (end == line.length() || line.charAt(end) == ' ') {
                return true;
            }
            at = line.indexOf(token, at + 1);
        }
        return false;
    }

    private static boolean isAbout(String line, Set<String> keys) {
        Matcher field = PERSON_FIELD.matcher(line);
        while (field.find()) {
            if (keys.contains(Person.keyOf(field.group(1)))) {
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
