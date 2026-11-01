package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imported people: lines of name,grade,terms,contact (the last two may be absent or blank), each a
 * person added in order exactly as the person form adds one, with the form's rules. A name, or a
 * contact, given twice in the paste (compared as the form compares them) refuses it. Blank lines are
 * skipped; a paste with no line is refused. It is one change: a line that is refused refuses them all.
 */
@Service
public class PersonImportService {

    private static final int NAME_MAX = 200;
    private static final int CONTACT_MAX = 200;
    private static final Pattern GRADE = Pattern.compile("[ABC]");
    private static final Pattern TERMS = Pattern.compile("\\d{0,4}");

    private record Line(String name, String grade, Integer terms, String contact) {}

    private final PersonService people;

    public PersonImportService(PersonService people) {
        this.people = people;
    }

    @Transactional
    public List<Person> importPeople(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        for (String raw : text.split("\\R")) {
            if (raw.isBlank()) continue;
            Line line = parse(raw);
            if (!names.add(Person.keyOf(line.name()))) throw new DuplicatePersonNameException();
            String key = Person.contactOf(line.contact());
            if (key != null && !contacts.add(key)) throw new DuplicatePersonContactException();
            lines.add(line);
        }
        if (lines.isEmpty()) throw new PersonImportRefusedException();
        List<Person> added = new ArrayList<>();
        for (Line line : lines) {
            added.add(people.add(sheetId, user, line.name(), line.grade(), line.terms(), line.contact()));
        }
        return added;
    }

    private Line parse(String raw) {
        String[] fields = raw.split(",", 4);
        if (fields.length < 2) throw new PersonImportRefusedException();
        String name = fields[0].strip();
        String grade = fields[1].strip();
        String terms = fields.length > 2 ? fields[2].strip() : "";
        String contact = fields.length > 3 ? fields[3].strip() : "";
        if (name.isEmpty() || name.length() > NAME_MAX || !GRADE.matcher(grade).matches()
                || !TERMS.matcher(terms).matches() || contact.length() > CONTACT_MAX) {
            throw new PersonImportRefusedException();
        }
        return new Line(name, grade, terms.isEmpty() ? null : Integer.valueOf(terms),
                contact.isEmpty() ? null : contact);
    }
}
