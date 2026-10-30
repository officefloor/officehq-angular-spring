package net.officefloor.hq.app.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The audit viewer: the audit lines of a sheet exactly as the audit file holds them, newest first. Given a
 * name, only the lines about that person: those carrying {@code name=} or {@code person=} that name, compared
 * as names are (case and surrounding spaces ignored); when that person was transferred here, the sheet they came
 * from's lines about them follow, newest first. It reads the file; it records nothing.
 */
@RestController
@RequestMapping("/api/sheets/{sheetId}/audit")
@Validated
public class AuditController {

    /** A field of a line: its key, and its value running up to the next field (a value may hold spaces). */
    private static final Pattern FIELD = Pattern.compile("(?:^|\\s)([A-Za-z][\\w-]*)=(.*?)(?=\\s[A-Za-z][\\w-]*=|$)");

    private final Audit audit;
    private final PersonRepository people;

    public AuditController(Audit audit, PersonRepository people) {
        this.audit = audit;
        this.people = people;
    }

    @GetMapping
    public List<String> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam(required = false) String name) {
        String sheet = sheetId.trim();
        String who = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        List<String> all = audit.lines();
        List<String> lines = about(all, sheet, who);
        if (!who.isEmpty()) {
            people.findBySheetIdAndNameIgnoreCase(sheet, name.strip()).map(Person::getOrigin)
                    .ifPresent(origin -> lines.addAll(about(all, origin, who)));
        }
        return lines;
    }

    /** The sheet's lines, newest first; given a name (lower-cased), only those about that person. */
    private static List<String> about(List<String> all, String sheet, String who) {
        List<String> lines = new ArrayList<>(all.stream()
                .filter(l -> carries(l, "sheet", sheet, false))
                .filter(l -> who.isEmpty() || carries(l, "name", who, true) || carries(l, "person", who, true))
                .toList());
        Collections.reverse(lines);
        return lines;
    }

    private static boolean carries(String line, String key, String value, boolean asName) {
        Matcher m = FIELD.matcher(line);
        while (m.find()) {
            if (!m.group(1).equals(key)) {
                continue;
            }
            String v = m.group(2);
            if (asName ? v.strip().toLowerCase(Locale.ROOT).equals(value) : v.equals(value)) {
                return true;
            }
        }
        return false;
    }
}
