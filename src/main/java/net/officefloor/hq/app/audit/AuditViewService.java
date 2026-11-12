package net.officefloor.hq.app.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import org.springframework.stereotype.Service;

/**
 * Reads a sheet's records back out of the audit file, newest first. It reads what the file holds;
 * it keeps no record of its own.
 */
@Service
public class AuditViewService {

    private final Audit audit;

    public AuditViewService(Audit audit) {
        this.audit = audit;
    }

    /**
     * The sheet's audit lines, newest first. With a name, only the lines about that person: those
     * carrying name=&lt;name&gt; or person=&lt;name&gt;, the name compared as names are (case and
     * surrounding spaces ignored).
     */
    public List<String> lines(String sheetId, String name) {
        Pattern sheet = Pattern.compile("(?:^| )sheet=" + Pattern.quote(sheetId) + "(?: |$)");
        String who = name == null ? "" : name.trim();
        Pattern person = who.isEmpty() ? null
                : Pattern.compile("(?:^| )(?:name|person)=" + Pattern.quote(who) + "(?= [A-Za-z][\\w-]*=|$)",
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<String> lines = new ArrayList<>();
        for (String line : audit.lines()) {
            if (sheet.matcher(line).find() && (person == null || person.matcher(line).find())) {
                lines.add(line);
            }
        }
        Collections.reverse(lines);
        return lines;
    }
}
