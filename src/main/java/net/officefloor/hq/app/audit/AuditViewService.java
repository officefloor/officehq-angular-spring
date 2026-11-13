package net.officefloor.hq.app.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimNotFoundException;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;

/**
 * Reads a sheet's records back out of the audit file, newest first. It reads what the file holds;
 * it keeps no record of its own.
 */
@Service
public class AuditViewService {

    private final Audit audit;
    private final PersonRepository people;
    private final SheetRepository sheets;
    private final ClaimRepository claims;

    public AuditViewService(Audit audit, PersonRepository people, SheetRepository sheets, ClaimRepository claims) {
        this.audit = audit;
        this.people = people;
        this.sheets = sheets;
        this.claims = claims;
    }

    /**
     * A claim's timeline: the sheet's audit lines about it, newest first, as the file holds them. A claim
     * with a number has the lines carrying number=&lt;its number&gt;; one with no number has the lines,
     * carrying no number, that name its person, amount and date together.
     */
    public List<String> claimLines(String sheetId, Long claimId) {
        Claim claim = claims.findByIdAndSheetId(claimId, sheetId).orElseThrow(ClaimNotFoundException::new);
        Pattern sheet = field("sheet", sheetId);
        List<Pattern> about;
        Pattern numbered = Pattern.compile("(?:^| )number=");
        if (claim.getNumber() != null) {
            about = List.of(field("number", claim.getNumber()));
            numbered = null;
        } else {
            String person = people.findByIdAndSheetId(claim.getPersonId(), sheetId).map(Person::getName).orElse(null);
            if (person == null) {
                return new ArrayList<>();
            }
            about = List.of(field("person", person), field("amount", claim.getAmount().toPlainString()),
                    field("date", claim.getDate().toString()));
        }
        List<String> lines = new ArrayList<>();
        for (String line : audit.lines()) {
            if (sheet.matcher(line).find() && (numbered == null || !numbered.matcher(line).find())
                    && about.stream().allMatch(p -> p.matcher(line).find())) {
                lines.add(line);
            }
        }
        Collections.reverse(lines);
        return lines;
    }

    /** A key=value field of a line, the value running up to the next field or the line's end. */
    private static Pattern field(String key, String value) {
        return Pattern.compile("(?:^| )" + key + "=" + Pattern.quote(value) + "(?= [A-Za-z][\\w-]*=|$)");
    }

    /**
     * The sheet's audit lines, newest first. With a name, only the lines about that person: those
     * carrying name=&lt;name&gt; or person=&lt;name&gt;, the name compared as names are (case and
     * surrounding spaces ignored); the lines about them on the sheet's descendants follow, each sheet's
     * newest first, the sheets by id; when the person of that name on the sheet was transferred here, the
     * lines about them on the sheet they came from follow, newest first, as that sheet's file holds them.
     */
    public List<String> lines(String sheetId, String name) {
        List<String> lines = sheetLines(sheetId, name);
        String who = name == null ? "" : name.trim();
        if (!who.isEmpty()) {
            for (String descendant : descendants(sheetId)) {
                lines.addAll(sheetLines(descendant, who));
            }
            String origin = people.findByNameOrAlias(sheetId, who).map(Person::getOriginSheet).orElse(null);
            if (origin != null && !origin.equals(sheetId)) {
                lines.addAll(sheetLines(origin, who));
            }
        }
        return lines;
    }

    /** The sheet's children, theirs and so on, by id. */
    private TreeSet<String> descendants(String sheetId) {
        TreeSet<String> found = new TreeSet<>();
        Deque<String> next = new ArrayDeque<>(List.of(sheetId));
        while (!next.isEmpty()) {
            for (Sheet child : sheets.findByParentOrderByIdAsc(next.pop())) {
                if (!child.getId().equals(sheetId) && found.add(child.getId())) {
                    next.add(child.getId());
                }
            }
        }
        return found;
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
