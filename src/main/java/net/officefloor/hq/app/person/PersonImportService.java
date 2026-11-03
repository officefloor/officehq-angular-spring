package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.officefloor.hq.app.grade.GradeService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The import box: lines of name,grade,terms,contact, each adding a person as the person form
 *  would, or, when the name matches a person on the sheet (by name or alias, as names are compared),
 *  editing that person as the person form would; the terms and contact may be absent or blank, and a
 *  blank grade is the sheet's default grade. */
@Service
public class PersonImportService {

    /** The person form's longest name and contact. */
    private static final int NAME_MAX = 200;
    private static final int CONTACT_MAX = 200;

    /** The person form's grade and terms. */
    private static final Pattern GRADE = Pattern.compile("[ABC]");
    private static final Pattern TERMS = Pattern.compile("\\s*\\d{0,4}\\s*");

    /** One parsed line: the name, the grade, the terms (null: none) and the contact (null: none). */
    private record Line(String name, String grade, Integer terms, String contact) {
    }

    /** One checked line: the person it updates (null: it adds one). */
    private record Planned(Line line, Person match) {
    }

    /** What an import did: the people added and the people updated, each in line order, and
     *  everyone it touched, in line order. */
    public record Result(List<Person> added, List<Person> updated, List<Person> people) {
    }

    private final PersonRepository people;
    private final PersonService service;
    private final SheetService sheets;
    private final GradeService grades;

    public PersonImportService(PersonRepository people, PersonService service, SheetService sheets,
            GradeService grades) {
        this.grades = grades;
        this.people = people;
        this.service = service;
        this.sheets = sheets;
    }

    /** Add or update a person per line, in order, checking every line first with the person form's
     *  rules; one change, so when any line is refused no one is added or changed and nothing is
     *  recorded. A line whose name matches a person on the sheet, by name or alias, updates them as
     *  the person form would: their grade from the sheet's today, and their terms and contact when
     *  the line gives them; their name is kept. Other lines add a person. Blank lines are skipped; an
     *  import with no lines is refused, and so is one repeating a name, a person or a contact within
     *  it. */
    @Transactional
    public Result importPeople(String sheetId, String user, String text) {
        List<Planned> lines = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<Long> matched = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        String defaultGrade = sheets.defaultGrade(sheetId);
        int n = 0;
        for (String raw : text.split("\\R")) {
            n++;
            if (raw.isBlank()) {
                continue;
            }
            Line line = parse(n, raw, defaultGrade);
            if (!names.add(line.name().toLowerCase(Locale.ROOT))) {
                throw refused(n, "the name \"" + line.name() + "\" is repeated in the import");
            }
            if (line.contact() != null && !contacts.add(line.contact())) {
                throw refused(n, "the contact \"" + line.contact() + "\" is repeated in the import");
            }
            Person match = people.findByNameOrAlias(sheetId, line.name()).orElse(null);
            if (match != null && !matched.add(match.getId())) {
                throw refused(n, "the person \"" + match.getName() + "\" is repeated in the import");
            }
            if (line.contact() != null && (match == null
                    ? people.existsBySheetIdAndContact(sheetId, line.contact())
                    : people.existsBySheetIdAndContactAndIdNot(sheetId, line.contact(), match.getId()))) {
                throw refused(n, "the contact \"" + line.contact() + "\" is already held on this sheet");
            }
            if (grades.isRetired(sheetId, line.grade())
                    && (match == null || !line.grade().equals(match.gradeOn(sheets.today(sheetId))))) {
                throw refused(n, "the grade " + line.grade() + " is retired");
            }
            lines.add(new Planned(line, match));
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line");
        }
        List<Person> added = new ArrayList<>();
        List<Person> updated = new ArrayList<>();
        List<Person> all = new ArrayList<>();
        for (Planned planned : lines) {
            Line line = planned.line();
            Person match = planned.match();
            Person person;
            if (match == null) {
                person = service.add(sheetId, user, line.name(), line.grade(), line.terms(), line.contact());
                added.add(person);
            } else {
                person = service.edit(sheetId, user, match.getId(), match.getVersion(), match.getName(),
                        line.grade(), null, line.terms() != null ? line.terms() : match.getTerms(),
                        line.contact() != null ? line.contact() : match.getContact());
                updated.add(person);
            }
            all.add(person);
        }
        return new Result(added, updated, all);
    }

    /** A line of name,grade,terms,contact: the last two may be absent or blank, and a blank grade
     *  is the default grade. */
    private static Line parse(int n, String raw, String defaultGrade) {
        String[] fields = raw.split(",", -1);
        if (fields.length < 2 || fields.length > 4) {
            throw refused(n, "expected name,grade,terms,contact");
        }
        String name = fields[0].trim();
        if (name.isEmpty()) {
            throw refused(n, "a name is required");
        }
        if (name.length() > NAME_MAX) {
            throw refused(n, "the name is too long");
        }
        String grade = fields[1].isBlank() ? defaultGrade : fields[1].trim();
        if (!GRADE.matcher(grade).matches()) {
            throw refused(n, "the grade must be A, B or C");
        }
        String terms = fields.length > 2 ? fields[2] : "";
        if (!TERMS.matcher(terms).matches()) {
            throw refused(n, "the terms must be a whole number of days");
        }
        String contact = fields.length > 3 ? fields[3] : "";
        if (contact.length() > CONTACT_MAX) {
            throw refused(n, "the contact is too long");
        }
        return new Line(name, grade, terms.isBlank() ? null : Integer.valueOf(terms.trim()),
                contact.isBlank() ? null : contact.trim().toLowerCase(Locale.ROOT));
    }

    private static ResponseStatusException refused(int n, String why) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line " + n + ": " + why);
    }
}
