package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.officefloor.hq.app.role.RoleService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The import box: lines of {@code name,grade,terms,contact}, taken in order (terms and contact may be
 * absent or blank). A line whose name matches a person on the sheet by name or alias (compared without
 * regard to case) updates them exactly as the person form edits them: their grade recorded from the
 * sheet's today, their terms and contact set when the line gives them, their name kept. Every other
 * line adds a person exactly as the person form adds them. Every line is held to the
 * person form's rules before anything is added, and a name or contact repeated within the import
 * refuses it, as does a person matched by two lines; the import is one change, so a refused line adds nothing and writes nothing. Blank
 * lines are skipped; an import with no lines is refused.
 */
@Service
public class PersonImportService {

    /** The longest name or contact the person form takes. */
    private static final int TEXT_MAX = 200;

    private record Line(String name, String grade, Integer terms, String contact) {
    }

    /** What an import did: the people it added and those it updated, each in line order. */
    public record Result(List<Person> added, List<Person> updated) {
    }

    private final PersonService people;
    private final PersonRepository repository;
    private final RoleService roles;

    public PersonImportService(PersonService people, PersonRepository repository, RoleService roles) {
        this.people = people;
        this.repository = repository;
        this.roles = roles;
    }

    @Transactional
    public Result importPeople(String sheetId, String user, String text) {
        roles.refuseViewer(sheetId, user);
        List<Line> lines = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        String[] raw = text.split("\\r?\\n|\\r");
        for (int i = 0; i < raw.length; i++) {
            if (!raw[i].isBlank()) {
                Line line = parse(i + 1, raw[i]);
                if (!names.add(line.name().toLowerCase(Locale.ROOT))) {
                    throw refused("Line " + (i + 1) + ": the name " + line.name() + " is already in this import.");
                }
                if (line.contact() != null && !contacts.add(line.contact())) {
                    throw refused("Line " + (i + 1) + ": the contact " + line.contact() + " is already in this import.");
                }
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line of name,grade,terms,contact.");
        }
        List<Person> added = new ArrayList<>();
        List<Person> updated = new ArrayList<>();
        Set<Long> matched = new HashSet<>();
        for (Line line : lines) {
            Person person = repository.findBySheetIdAndNameOrAlias(sheetId, line.name()).orElse(null);
            if (person == null) {
                added.add(people.add(sheetId, user, line.name(), line.grade(), line.terms(), line.contact()));
            } else if (!matched.add(person.getId())) {
                throw refused("The name " + line.name() + " matches " + person.getName() + ", already in this import.");
            } else {
                updated.add(people.update(sheetId, user, person.getId(), person.getVersion(), person.getName(),
                        line.grade(), null, line.terms() != null ? line.terms() : person.getTerms(),
                        line.contact() != null ? line.contact() : person.getContact()));
            }
        }
        return new Result(added, updated);
    }

    private static Line parse(int number, String text) {
        String[] fields = text.split(",", -1);
        if (fields.length < 2 || fields.length > 4) {
            throw refused("Line " + number + " needs a name and a grade, then optionally terms and a contact.");
        }
        String name = fields[0].trim();
        if (name.isEmpty() || name.length() > TEXT_MAX) {
            throw refused("Line " + number + ": enter a name.");
        }
        String grade = fields[1].trim();
        if (!grade.matches("[ABC]")) {
            throw refused("Line " + number + ": choose a grade of A, B or C.");
        }
        String termsText = fields.length > 2 ? fields[2].trim() : "";
        if (!termsText.isEmpty() && !termsText.matches("\\d{1,6}")) {
            throw refused("Line " + number + ": enter the terms as a whole number of days, or leave them blank.");
        }
        String contact = fields.length > 3 ? fields[3].trim().toLowerCase(Locale.ROOT) : "";
        if (contact.length() > TEXT_MAX) {
            throw refused("Line " + number + ": the contact is too long.");
        }
        return new Line(name, grade, termsText.isEmpty() ? null : Integer.valueOf(termsText),
                contact.isEmpty() ? null : contact);
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
