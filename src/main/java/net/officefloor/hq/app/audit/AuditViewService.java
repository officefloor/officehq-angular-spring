package net.officefloor.hq.app.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.stereotype.Service;

/**
 * Reads a sheet's records back out of the audit file, newest first. It reads what the file holds;
 * it keeps no record of its own.
 */
@Service
public class AuditViewService {

    private final Audit audit;
    private final PersonRepository people;

    public AuditViewService(Audit audit, PersonRepository people) {
        this.audit = audit;
        this.people = people;
    }

    /**
     * The sheet's audit lines, newest first. With a name, only the lines about that person: those
     * carrying name=&lt;name&gt; or person=&lt;name&gt;, the name compared as names are (case and
     * surrounding spaces ignored); when the person of that name on the sheet was transferred here, the
     * lines about them on the sheet they came from follow, newest first, as that sheet's file holds them.
     */
    public List<String> lines(String sheetId, String name) {
        List<String> lines = sheetLines(sheetId, name);
        String who = name == null ? "" : name.trim();
        if (!who.isEmpty()) {
            String origin = people.findByNameOrAlias(sheetId, who).map(Person::getOriginSheet).orElse(null);
            if (origin != null && !origin.equals(sheetId)) {
                lines.addAll(sheetLines(origin, who));
            }
        }
        return lines;
    }

    private List<String> sheetLines(String sheetId, String name) {
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
