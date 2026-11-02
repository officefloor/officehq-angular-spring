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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The audit viewer: the sheet's lines exactly as the audit file holds them, newest first,
 *  optionally only those about one person. It reads the file; it records nothing. */
@RestController
@RequestMapping("/api/sheets/{sheetId}/audit")
@Validated
public class AuditController {

    /** A field of a record: its key, then its value up to the next field (a value may hold spaces). */
    private static final Pattern FIELD = Pattern.compile("(?<= )([A-Za-z][\\w-]*)=(.*?)(?= [A-Za-z][\\w-]*=|$)");

    private final Audit audit;

    public AuditController(Audit audit) {
        this.audit = audit;
    }

    @GetMapping
    public List<String> list(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @RequestParam(required = false) @Size(max = 200) String name) {
        String sheet = sheetId.trim();
        String person = name == null || name.isBlank() ? null : key(name);
        List<String> shown = new ArrayList<>();
        for (String line : audit.lines()) {
            if (matches(line, sheet, person)) {
                shown.add(line);
            }
        }
        Collections.reverse(shown);
        return shown;
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
