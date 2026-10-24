package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds a person per pasted line of {@code name,grade,terms,contact}, in order, each through
 * {@link PersonService#add} exactly as the person form adds them; terms and contact may be blank or left
 * out. Every line is first held to the person form's rules, and a name or contact repeated within the
 * paste is refused too: it is one change, so when any line is refused no one is added and nothing is
 * recorded. Blank lines are skipped. */
@Service
public class PersonImportService {

    /** A grade as the person form takes it. */
    private static final Pattern GRADE = Pattern.compile("[ABC]");

    /** Terms as the person form takes them. */
    private static final Pattern TERMS = Pattern.compile("\\s*(\\d{1,4})?\\s*");

    /** The longest name and contact the person form takes. */
    private static final int MAX = 200;

    private final PersonService service;
    private final PersonRepository people;
    private final PersonTree tree;

    public PersonImportService(PersonService service, PersonRepository people, PersonTree tree) {
        this.service = service;
        this.people = people;
        this.tree = tree;
    }

    private record Line(String name, String grade, Integer terms, String contact) {
    }

    /** The people added, in the order of their lines. */
    @Transactional
    public List<Person> importPeople(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        for (String raw : text.split("\\r?\\n")) {
            if (raw.isBlank()) continue;
            String[] fields = raw.split(",", -1);
            if (fields.length < 2 || fields.length > 4) throw new PersonImportException();
            String name = fields[0].trim();
            String grade = fields[1].trim();
            String terms = fields.length > 2 ? fields[2] : "";
            String contact = fields.length > 3 ? Person.contactOf(fields[3]) : null;
            if (name.isEmpty() || name.length() > MAX || !GRADE.matcher(grade).matches()
                    || !TERMS.matcher(terms).matches() || (contact != null && contact.length() > MAX)) {
                throw new PersonImportException();
            }
            String key = Person.keyOf(name);
            if (!names.add(key) || people.findBySheetIdAndNameKey(sheetId, key).isPresent()
                    || people.findBySheetIdAndAliasKey(sheetId, key).isPresent() || tree.taken(sheetId, name)) {
                throw new PersonImportException();
            }
            if (contact != null && (!contacts.add(contact) || people.findBySheetIdAndContact(sheetId, contact).isPresent())) {
                throw new PersonImportException();
            }
            lines.add(new Line(name, grade, terms.isBlank() ? null : Integer.valueOf(terms.trim()), contact));
        }
        if (lines.isEmpty()) throw new PersonImportException();
        List<Person> added = new ArrayList<>();
        for (Line line : lines) {
            added.add(service.add(sheetId, user, line.name(), line.grade(), line.terms(), line.contact()));
        }
        return added;
    }
}
