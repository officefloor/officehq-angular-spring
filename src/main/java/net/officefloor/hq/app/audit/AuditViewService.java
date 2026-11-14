package net.officefloor.hq.app.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import org.springframework.stereotype.Service;

/**
 * Reads the audit file back for the viewer: it is not a second record, only what the file holds.
 */
@Service
public class AuditViewService {

    /** The start of a field: a space, then its key and '='. A field's value runs to the next field. */
    private static final Pattern FIELD = Pattern.compile(" ([a-z][a-z0-9-]*)=");

    private final Audit audit;

    public AuditViewService(Audit audit) {
        this.audit = audit;
    }

    /**
     * The sheet's lines, newest first. With a non-blank name, only the lines whose name= or person=
     * field is that name, compared as names are (case and surrounding spaces ignored).
     */
    public List<String> lines(String sheetId, String name) {
        String wanted = name == null ? "" : normal(name);
        List<String> found = new ArrayList<>();
        for (String line : audit.lines()) {
            if (!field(line, "sheet").filter(sheetId::equals).isPresent()) {
                continue;
            }
            if (!wanted.isEmpty() && !about(line, wanted)) {
                continue;
            }
            found.add(line);
        }
        Collections.reverse(found);
        return found;
    }

    private static boolean about(String line, String wanted) {
        return field(line, "name").map(AuditViewService::normal).filter(wanted::equals).isPresent()
                || field(line, "person").map(AuditViewService::normal).filter(wanted::equals).isPresent();
    }

    /** The value of the line's first field with this key, up to the next field. */
    private static Optional<String> field(String line, String key) {
        Matcher m = FIELD.matcher(line);
        while (m.find()) {
            if (!m.group(1).equals(key)) {
                continue;
            }
            int start = m.end();
            int end = m.find() ? m.start() : line.length();
            return Optional.of(line.substring(start, end));
        }
        return Optional.empty();
    }

    private static String normal(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
