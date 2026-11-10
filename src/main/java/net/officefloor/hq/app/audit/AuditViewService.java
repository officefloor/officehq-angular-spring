package net.officefloor.hq.app.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import org.springframework.stereotype.Service;

/**
 * Reads a sheet's records back from the audit file, newest first, exactly as the file holds them.
 * It is a view of the file, not a second record: nothing is kept or written here.
 */
@Service
public class AuditViewService {

    /** What may follow a field's value: the line's end, or the next {@code key=} field. */
    private static final Pattern NEXT_FIELD = Pattern.compile("^( [A-Za-z][A-Za-z0-9-]*=.*)?$");

    private final Audit audit;

    public AuditViewService(Audit audit) {
        this.audit = audit;
    }

    /**
     * The sheet's records, newest first; with a name (blank for none), only those carrying
     * {@code name=<name>} or {@code person=<name>}, the name compared ignoring case and surrounding
     * spaces.
     */
    public List<String> lines(String sheet, String name) {
        String person = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String line : audit.lines()) {
            if (ofSheet(line, sheet) && (person.isEmpty() || about(line, person))) {
                found.add(line);
            }
        }
        Collections.reverse(found);
        return found;
    }

    private static boolean ofSheet(String line, String sheet) {
        String[] fields = line.split(" ", 3);
        return fields.length > 1 && fields[1].equals("sheet=" + sheet);
    }

    /** Whether the line carries name= or person= with this (lower-cased) name as its whole value. */
    private static boolean about(String line, String person) {
        String lower = line.toLowerCase(Locale.ROOT);
        return carries(line, lower, " name=" + person) || carries(line, lower, " person=" + person);
    }

    private static boolean carries(String line, String lower, String field) {
        for (int at = lower.indexOf(field); at >= 0; at = lower.indexOf(field, at + 1)) {
            if (NEXT_FIELD.matcher(line.substring(at + field.length())).matches()) {
                return true;
            }
        }
        return false;
    }
}
