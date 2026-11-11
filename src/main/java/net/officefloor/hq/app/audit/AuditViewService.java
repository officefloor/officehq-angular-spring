package net.officefloor.hq.app.audit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.math.RoundingMode;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Reads a sheet's records back from the audit file, newest first, exactly as the file holds them.
 * It is a view of the file, not a second record: nothing is kept or written here.
 */
@Service
public class AuditViewService {

    /** What may follow a field's value: the line's end, or the next {@code key=} field. */
    private static final Pattern NEXT_FIELD = Pattern.compile("^( [A-Za-z][A-Za-z0-9-]*=.*)?$");

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
     * A claim's timeline: the sheet's lines about the claim, newest first, exactly as the file holds
     * them. A numbered claim's lines are those carrying {@code number=<its number>}; a claim with no
     * number's are those carrying {@code person=<its person's name>}, {@code amount=<its amount>} and
     * {@code date=<its date>} together and no number (a line with a number is about the numbered
     * claim it names). Not found (404) when the sheet has no such claim.
     */
    public List<String> claimLines(String sheet, Long id) {
        Claim claim = claims.findByIdAndSheetId(id, sheet)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<String> fields;
        boolean numbered = claim.getNumber() != null;
        if (claim.getNumber() != null) {
            fields = List.of(" number=" + claim.getNumber());
        } else {
            String name = people.findByIdAndSheetId(claim.getPersonId(), sheet).map(Person::getName)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            fields = List.of(" person=" + name,
                    " amount=" + claim.getAmount().setScale(2, RoundingMode.UNNECESSARY).toPlainString(),
                    " date=" + claim.getDate());
        }
        List<String> found = new ArrayList<>();
        for (String line : audit.lines()) {
            if (ofSheet(line, sheet) && fields.stream().allMatch(field -> carries(line, line, field))
                    && (numbered || !line.contains(" number="))) {
                found.add(line);
            }
        }
        Collections.reverse(found);
        return found;
    }

    /**
     * The sheet's activity: for every user and every event in the sheet's audit lines, one line
     * {@code <user> <EVENT> <count>}, sorted by user then event. A line with no {@code by=} counts
     * for no one.
     */
    public List<String> activity(String sheet) {
        TreeMap<String, TreeMap<String, Integer>> counts = new TreeMap<>();
        for (String line : audit.lines()) {
            if (!ofSheet(line, sheet)) {
                continue;
            }
            String[] fields = line.split(" ");
            String by = null;
            for (String field : fields) {
                if (field.startsWith("by=")) {
                    by = field.substring(3);
                    break;
                }
            }
            if (by == null || by.isEmpty()) {
                continue;
            }
            counts.computeIfAbsent(by, u -> new TreeMap<>()).merge(fields[0], 1, Integer::sum);
        }
        List<String> found = new ArrayList<>();
        counts.forEach((user, events) -> events.forEach((event, count) -> found.add(user + " " + event + " " + count)));
        return found;
    }

    /**
     * The sheet's records, newest first; with a name (blank for none), only those carrying
     * {@code name=<name>} or {@code person=<name>}, the name compared ignoring case and surrounding
     * spaces. With a name, the lines about them on the sheet's descendants (its children, their
     * children, and so on) follow, each sheet's newest first, the sheets in order of id. When the
     * name is a person transferred in, the lines about them from the sheet they came from follow
     * last, newest first, as that sheet's lines are.
     */
    public List<String> lines(String sheet, String name) {
        String person = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        List<String> all = audit.lines();
        List<String> found = linesOf(all, sheet, person);
        if (!person.isEmpty()) {
            for (String descendant : descendants(sheet)) {
                found.addAll(linesOf(all, descendant, person));
            }
            people.findBySheetIdAndNameIgnoreCase(sheet, name.trim()).map(Person::getOriginSheet)
                    .ifPresent(origin -> found.addAll(linesOf(all, origin, person)));
        }
        return found;
    }

    /** The ids of the sheet's descendants, at every level, in order of id; the sheet itself never. */
    private List<String> descendants(String sheet) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> todo = new ArrayList<>(List.of(sheet));
        while (!todo.isEmpty()) {
            String at = todo.remove(todo.size() - 1);
            for (Sheet child : sheets.findByParentIdOrderByIdAsc(at)) {
                if (!child.getId().equals(sheet) && seen.add(child.getId())) {
                    todo.add(child.getId());
                }
            }
        }
        List<String> ids = new ArrayList<>(seen);
        Collections.sort(ids);
        return ids;
    }

    /** The sheet's lines, newest first, only those about the (lower-cased) person when one is given. */
    private static List<String> linesOf(List<String> all, String sheet, String person) {
        List<String> found = new ArrayList<>();
        for (String line : all) {
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
