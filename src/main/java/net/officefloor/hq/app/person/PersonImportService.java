package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.officefloor.hq.app.grade.GradeService;
import net.officefloor.hq.app.settings.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Takes a pasted line of {@code name,grade,terms,contact} per person, in order; terms and contact may
 * be blank or left out, and a blank grade is the sheet's default grade. A line whose name matches a person on the sheet (by name or alias, as names are
 * compared) updates that person through {@link PersonService#update} exactly as the person form saves
 * them: their grade from the sheet's today, and their terms and contact when the line gives them. Any
 * other line adds a person through {@link PersonService#add} exactly as the person form adds them. Every
 * line is first held to the person form's rules, and a name, person or contact repeated within the
 * paste is refused too: it is one change, so when any line is refused no one is added or updated and
 * nothing is recorded. Blank lines are skipped. */
@Service
public class PersonImportService {

    /** A grade as the person form takes it. */
    private static final Pattern GRADE = Pattern.compile("[A-Z]{1,3}");

    /** Terms as the person form takes them. */
    private static final Pattern TERMS = Pattern.compile("\\s*(\\d{1,4})?\\s*");

    /** The longest name and contact the person form takes. */
    private static final int MAX = 200;

    private final PersonService service;
    private final PersonRepository people;
    private final PersonTree tree;
    private final SettingsService settings;
    private final GradeService grades;

    public PersonImportService(PersonService service, PersonRepository people, PersonTree tree, SettingsService settings,
            GradeService grades) {
        this.grades = grades;
        this.service = service;
        this.people = people;
        this.tree = tree;
        this.settings = settings;
    }

    /** A line to import: {@code match} is the person on the sheet it updates, null when it adds. */
    private record Line(String name, String grade, Integer terms, String contact, Person match) {
    }

    /** What an import did: the people it added and those it updated, each in the order of their lines. */
    public record Result(List<Person> added, List<Person> updated) {
    }

    @Transactional
    public Result importPeople(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<Long> matched = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        String defaultGrade = grades.defaultGradeFor(sheetId, settings.defaultGradeInForce(sheetId));
        for (String raw : text.split("\\r?\\n")) {
            if (raw.isBlank()) continue;
            String[] fields = raw.split(",", -1);
            if (fields.length < 2 || fields.length > 4) throw new PersonImportException();
            String name = fields[0].trim();
            String grade = fields[1].isBlank() ? defaultGrade : fields[1].trim();
            String terms = fields.length > 2 ? fields[2] : "";
            String contact = fields.length > 3 ? Person.contactOf(fields[3]) : null;
            if (name.isEmpty() || name.length() > MAX || !GRADE.matcher(grade).matches()
                    || !TERMS.matcher(terms).matches() || (contact != null && contact.length() > MAX)) {
                throw new PersonImportException();
            }
            if (!grades.isGrade(sheetId, grade) || grades.isRetired(sheetId, grade)) throw new PersonImportException();
            if (!names.add(Person.keyOf(name))) throw new PersonImportException();
            Person match = people.findBySheetIdAndNameOrAlias(sheetId, name).orElse(null);
            if (match == null) {
                if (tree.taken(sheetId, name)) throw new PersonImportException();
            } else if (!matched.add(match.getId()) || tree.clashes(match)) {
                throw new PersonImportException();
            }
            if (contact != null && (!contacts.add(contact) || people.findBySheetIdAndContact(sheetId, contact)
                    .filter(other -> match == null || !other.getId().equals(match.getId())).isPresent())) {
                throw new PersonImportException();
            }
            lines.add(new Line(name, grade, terms.isBlank() ? null : Integer.valueOf(terms.trim()), contact, match));
        }
        if (lines.isEmpty()) throw new PersonImportException();
        List<Person> added = new ArrayList<>();
        List<Person> updated = new ArrayList<>();
        for (Line line : lines) {
            Person match = line.match();
            if (match == null) {
                added.add(service.add(sheetId, user, line.name(), line.grade(), line.terms(), line.contact()));
            } else {
                updated.add(service.update(sheetId, user, match.getId(), match.getVersion(), match.getName(),
                        line.grade(), null, line.terms() != null ? line.terms() : match.getTerms(),
                        line.contact() != null ? line.contact() : match.getContact()));
            }
        }
        return new Result(added, updated);
    }
}
