package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.setting.SheetSettingRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImportPeopleService {

    /** A pasted line, read: the person's fields as the person form takes them; no grade when it is blank. */
    private record Line(int number, String name, Grade grade, Integer terms, String contact) {
    }

    /** What an import did: the people of its lines, in order, and how many it added and how many it updated. */
    public record Result(List<Person> people, int added, int updated) {
    }

    private final PersonService service;
    private final PersonRepository repository;
    private final SheetSettingRepository settings;

    public ImportPeopleService(PersonService service, PersonRepository repository, SheetSettingRepository settings) {
        this.service = service;
        this.repository = repository;
        this.settings = settings;
    }

    /** Add or update a person per pasted line of name,grade,terms,contact, in order, each as the person
     *  form would. A line whose name matches a person on the sheet, by name or alias as names are compared,
     *  updates them: their grade from the sheet's today, and their terms and contact when the line gives
     *  them. Other lines add a person. Every line is read first with the form's rules; one change: when any
     *  line is refused, nothing is imported and nothing is written. Blank lines are skipped; a paste with
     *  no lines is refused, as is one repeating a name or a contact of an earlier line, compared as the
     *  form compares them, or naming the same person as an earlier line. A line with a blank grade takes
     *  the sheet's default grade. */
    @Transactional
    public Result importPeople(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        int number = 0;
        for (String raw : text.split("\\r?\\n")) {
            number++;
            if (raw.isBlank()) continue;
            Line line = read(raw, number);
            if (!names.add(Person.keyOf(line.name()))) {
                throw refused(number, "the name '" + line.name() + "' is repeated");
            }
            String contactKey = Person.contactOf(line.contact());
            if (contactKey != null && !contacts.add(contactKey)) {
                throw refused(number, "the contact '" + contactKey + "' is repeated");
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Paste at least one line.");
        }
        Grade defaultGrade = SettingService.defaultGradeOf(settings, sheetId);
        List<Person> people = new ArrayList<>();
        Set<Long> updatedIds = new HashSet<>();
        int added = 0;
        for (Line line : lines) {
            Optional<Person> match = repository.findByNameOrAlias(sheetId, Person.keyOf(line.name()));
            try {
                if (match.isPresent()) {
                    Person person = match.get();
                    if (!updatedIds.add(person.getId())) {
                        throw refused(line.number(), "'" + person.getName() + "' is named by an earlier line");
                    }
                    people.add(service.update(sheetId, user, person.getId(), person.getVersion(), person.getName(),
                            gradeOf(line, defaultGrade), null, line.terms() != null ? line.terms() : person.getTerms(),
                            line.contact().isEmpty() ? person.getContact() : line.contact()));
                } else {
                    people.add(service.add(sheetId, user, line.name(), gradeOf(line, defaultGrade), line.terms(), line.contact()));
                    added++;
                }
            } catch (ResponseStatusException e) {
                if (e.getReason() != null && e.getReason().startsWith("Line ")) throw e;
                throw new ResponseStatusException(e.getStatusCode(), "Line " + line.number() + ": " + e.getReason());
            }
        }
        return new Result(people, added, updatedIds.size());
    }

    private static Grade gradeOf(Line line, Grade defaultGrade) {
        return line.grade() != null ? line.grade() : defaultGrade;
    }

    /** A line of name,grade,terms,contact; the grade may be blank, and the terms and contact blank or absent. */
    private static Line read(String raw, int number) {
        String[] fields = raw.split(",", 4);
        if (fields.length < 2) throw refused(number, "it needs at least a name and a grade");
        String name = fields[0].trim();
        if (name.isEmpty()) throw refused(number, "the name is blank");
        if (name.length() > 200) throw refused(number, "the name is longer than 200 characters");
        Grade grade = null;
        try {
            if (!fields[1].isBlank()) grade = Grade.valueOf(fields[1].trim());
        } catch (IllegalArgumentException e) {
            throw refused(number, "the grade is not one of A, B or C");
        }
        String terms = fields.length > 2 ? fields[2].trim() : "";
        if (!terms.matches("\\d{0,5}")) throw refused(number, "the terms are not a whole number of days");
        String contact = fields.length > 3 ? fields[3].trim() : "";
        if (contact.length() > 200) throw refused(number, "the contact is longer than 200 characters");
        return new Line(number, name, grade, terms.isEmpty() ? null : Integer.valueOf(terms), contact);
    }

    private static ResponseStatusException refused(int number, String why) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Line " + number + ": " + why + ".");
    }
}
