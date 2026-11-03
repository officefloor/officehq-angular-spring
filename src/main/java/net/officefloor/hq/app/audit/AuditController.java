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
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.person.Person;
import net.officefloor.hq.app.person.PersonRepository;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final ClaimRepository claims;

    public AuditController(Audit audit, PersonRepository people, SheetRepository sheets, ClaimRepository claims) {
        this.audit = audit;
        this.people = people;
        this.sheets = sheets;
        this.claims = claims;
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

    /** A claim's timeline: the sheet's lines about the claim, newest first, exactly as the file holds
     *  them. A numbered claim's lines are those carrying its number; a claim with no number's are
     *  those carrying no number that name its person, amount and date together. */
    @GetMapping("/claims/{claimId}")
    public List<String> timeline(@PathVariable @NotBlank @Size(max = 100) String sheetId,
            @PathVariable Long claimId) {
        String sheet = sheetId.trim();
        Claim claim = claims.findByIdAndSheetId(claimId, sheet)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such claim"));
        String number = claim.getNumber();
        String person = people.findById(claim.getPersonId()).map(p -> key(p.getName())).orElse(null);
        String amount = claim.getAmount().setScale(2).toPlainString();
        String date = String.valueOf(claim.getDate());
        List<String> shown = new ArrayList<>();
        for (String line : audit.lines()) {
            if (!sheet.equals(field(line, "sheet"))) {
                continue;
            }
            String lineNumber = field(line, "number");
            boolean about = number != null
                    ? number.equals(lineNumber)
                    : lineNumber == null && person != null && field(line, "person") != null
                            && person.equals(key(field(line, "person")))
                            && amount.equals(field(line, "amount")) && date.equals(field(line, "date"));
            if (about) {
                shown.add(line);
            }
        }
        Collections.reverse(shown);
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
