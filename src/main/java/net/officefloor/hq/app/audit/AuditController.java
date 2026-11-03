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
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit viewer: the sheet's lines exactly as the audit file holds them, newest first,
 *  optionally only those about one person. For a person, this sheet's lines about them are
 *  followed by each of its descendants' lines about them (sheets by id, each newest first), then,
 *  for a person transferred here, by the sheet they came from's lines about them, up to their
 *  transfer, newest first. It reads the file; it records nothing. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/audit")
@Validated
public class AuditController {

    /** A field of a record: its key, then its value up to the next field (a value may hold spaces). */
    private static final Pattern FIELD = Pattern.compile("(?<= )([A-Za-z][\\w-]*)=(.*?)(?= [A-Za-z][\\w-]*=|$)");

    private final Audit audit;
    private final PersonRepository people;
    private final SheetRepository sheets;

    public AuditController(Audit audit, PersonRepository people, SheetRepository sheets) {
        this.audit = audit;
        this.people = people;
        this.sheets = sheets;
    }

    @GetMapping
    public List<String> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam(required = false) @Size(max = 200) String name) {
        String sheet = sheetId.trim();
        String person = name == null || name.isBlank() ? null : key(name);
        List<String> lines = audit.lines();
        List<String> shown = about(lines, sheet, person);
        if (person != null) {
            sheets.descendants(sheet).stream().sorted()
                    .forEach(descendant -> shown.addAll(about(lines, descendant, person)));
            String origin = people.findBySheetIdAndNameIgnoreCase(sheet, name.trim()).map(Person::getOrigin)
                    .orElse(null);
            if (origin != null) {
                shown.addAll(untilTransfer(about(lines, origin, person), sheet));
            }
        }
        return shown;
    }

    /** The sheet's lines (about the person, when one is given), newest first. */
    private static List<String> about(List<String> lines, String sheet, String person) {
        List<String> shown = new ArrayList<>();
        for (String line : lines) {
            if (matches(line, sheet, person)) {
                shown.add(line);
            }
        }
        Collections.reverse(shown);
        return shown;
    }

    /** The lines (newest first) from the latest transfer to the sheet given on; all of them when
     *  there is none. */
    private static List<String> untilTransfer(List<String> newestFirst, String toSheet) {
        for (int i = 0; i < newestFirst.size(); i++) {
            String line = newestFirst.get(i);
            if (line.startsWith("PERSON_TRANSFERRED ") && toSheet.equals(field(line, "to"))) {
                return newestFirst.subList(i, newestFirst.size());
            }
        }
        return newestFirst;
    }

    /** The value of the line's field, or null when it has none. */
    private static String field(String line, String key) {
        Matcher m = FIELD.matcher(line);
        while (m.find()) {
            if (m.group(1).equals(key)) {
                return m.group(2);
            }
        }
        return null;
    }

    /** Whether the line is of the sheet and, when a person is asked for, carries name= or person= of them. */
    private static boolean matches(String line, String sheet, String person) {
        boolean ofSheet = false;
        boolean aboutPerson = person == null;
        Matcher m = FIELD.matcher(line);
        while (m.find()) {
            String field = m.group(1);
            String value = m.group(2);
            if (field.equals("sheet") && value.equals(sheet)) {
                ofSheet = true;
            } else if (person != null && (field.equals("name") || field.equals("person")) && key(value).equals(person)) {
                aboutPerson = true;
            }
        }
        return ofSheet && aboutPerson;
    }

    /** A name as names are compared: case and surrounding spaces ignored. */
    private static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
