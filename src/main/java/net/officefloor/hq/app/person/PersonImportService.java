package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import net.officefloor.hq.app.setting.SettingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imported people: lines of name,grade,terms,contact (the last two may be absent or blank; a blank grade
 * takes the sheet's default grade, A when blank), taken in
 * order with the person form's rules. A line whose name is a person's on the sheet (by name or alias, as
 * names are compared) edits them exactly as the form does: name kept, the grade recorded from the
 * sheet's today, and terms and contact set when the line gives them. Any other line adds a person. A name, or a
 * contact, given twice in the paste (compared as the form compares them) refuses it. Blank lines are
 * skipped; a paste with no line is refused. It is one change: a line that is refused refuses them all.
 */
@Service
public class PersonImportService {

    private static final int NAME_MAX = 200;
    private static final int CONTACT_MAX = 200;
    private static final Pattern GRADE = Pattern.compile("[ABC]?");
    private static final Pattern TERMS = Pattern.compile("\\d{0,4}");

    private record Line(String name, String grade, Integer terms, String contact) {}

    /** The people an import added, and those it updated, each in the order of their lines. */
    public record Result(List<Person> added, List<Person> updated) {}

    private final PersonService people;
    private final PersonRepository repository;
    private final SettingService settings;

    public PersonImportService(PersonService people, PersonRepository repository, SettingService settings) {
        this.people = people;
        this.repository = repository;
        this.settings = settings;
    }

    @Transactional
    public Result importPeople(String sheetId, String user, String text) {
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
        String defaultGrade = settings.gradeForNew(sheetId);
        List<Person> added = new ArrayList<>();
        List<Person> updated = new ArrayList<>();
        for (Line parsed : lines) {
            Line line = parsed.grade().isEmpty()
                    ? new Line(parsed.name(), defaultGrade, parsed.terms(), parsed.contact()) : parsed;
            Optional<Person> existing = repository.findByNameOrAlias(sheetId, line.name());
            if (existing.isPresent()) {
                Person person = existing.get();
                updated.add(people.edit(sheetId, user, person.getId(), person.getVersion(), person.getName(),
                        line.grade(), null, line.terms() != null ? line.terms() : person.getTerms(),
                        line.contact() != null ? line.contact() : person.getContact()));
            } else {
                added.add(people.add(sheetId, user, line.name(), line.grade(), line.terms(), line.contact()));
            }
        }
        return new Result(added, updated);
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
