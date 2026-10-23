package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The import box: lines of name,grade,terms,contact each add a person, in order, as the person form
 * would; a line whose name matches a person on the sheet, by name or alias as names are compared, instead
 * updates them as the person form would: their grade recorded from the sheet's today, and their terms and
 * contact set when the line gives them. A blank grade is the sheet's default grade. The terms and the contact may be absent or blank. Every line is
 * held to the person form's rules, and a name, person or contact repeated within the paste is refused; it
 * is one change, so when any line is refused nothing is imported and nothing is recorded.
 */
@Service
public class ImportService {

    /** The longest name or contact the person form takes. */
    private static final int TEXT_MAX = 200;

    /** The most days of terms the person form takes. */
    private static final int TERMS_MAX = 36500;

    /** The most lines one import takes. */
    static final int LINES_MAX = 1000;

    /** A line read from the paste, checked against the form's rules. */
    private record Line(String name, String grade, Integer terms, String contact) {
    }

    /** The people an import added and those it updated, each in the order of their lines. */
    public record Imported(List<Person> added, List<Person> updated) {
    }

    private final PersonService people;
    private final PersonRepository store;
    private final SheetService sheets;

    public ImportService(PersonService people, PersonRepository store, SheetService sheets) {
        this.people = people;
        this.store = store;
        this.sheets = sheets;
    }

    /** Add or update a person per line of the text, in order; refused as a whole when any line is refused. */
    @Transactional
    public Imported importPeople(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        String[] raw = text == null ? new String[0] : text.split("\\r?\\n");
        for (int i = 0; i < raw.length; i++) {
            if (raw[i].isBlank()) {
                continue;
            }
            Line line = line(i + 1, raw[i]);
            if (!names.add(Person.keyOf(line.name()))) {
                throw refused("Line " + (i + 1) + ": " + line.name() + " is named more than once.");
            }
            String contact = Person.contactOf(line.contact());
            if (contact != null && !contacts.add(contact)) {
                throw refused("Line " + (i + 1) + ": the contact " + contact + " is given more than once.");
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line of name,grade,terms,contact.");
        }
        if (lines.size() > LINES_MAX) {
            throw refused("Paste at most " + LINES_MAX + " lines at once.");
        }
        List<Person> added = new ArrayList<>();
        List<Person> updated = new ArrayList<>();
        Set<Long> matched = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String grade = lines.get(i).grade();
            if (grade != null) {
                try {
                    people.requireGradeOpen(sheetId, grade);
                } catch (ResponseStatusException retired) {
                    throw refused("Line " + (i + 1) + ": the grade " + grade + " is retired.");
                }
            }
        }
        String defaultGrade = sheets.defaultGrade(sheetId);
        int number = 0;
        for (Line line : lines) {
            number++;
            Person existing = store.findBySheetIdAndName(sheetId, line.name()).orElse(null);
            if (existing == null) {
                added.add(people.add(sheetId, user, line.name(), line.grade() != null ? line.grade() : defaultGrade, line.terms(), line.contact()));
                continue;
            }
            if (!matched.add(existing.getId())) {
                throw refused("Line " + number + ": " + existing.getName() + " is named more than once.");
            }
            boolean termsGiven = line.terms() != null;
            boolean contactGiven = line.contact() != null;
            updated.add(people.edit(sheetId, user, existing.getId(), existing.getVersion(), existing.getName(),
                    line.grade() != null ? line.grade() : defaultGrade, null, termsGiven ? line.terms() : existing.getTerms(),
                    contactGiven ? line.contact() : existing.getContact(), termsGiven, contactGiven));
        }
        return new Imported(added, updated);
    }

    private Line line(int number, String text) {
        String[] fields = text.split(",", -1);
        if (fields.length < 2) {
            throw refused("Line " + number + " needs at least a name and a grade.");
        }
        if (fields.length > 4) {
            throw refused("Line " + number + " has more than name,grade,terms,contact.");
        }
        String name = fields[0].trim();
        if (name.isEmpty()) {
            throw refused("Line " + number + " has no name.");
        }
        if (name.length() > TEXT_MAX) {
            throw refused("Line " + number + ": the name is longer than " + TEXT_MAX + " characters.");
        }
        String grade = fields[1].trim();
        if (!grade.isEmpty() && !grade.matches("[ABC]")) {
            throw refused("Line " + number + ": the grade must be A, B, C or blank.");
        }
        Integer terms = null;
        String days = fields.length > 2 ? fields[2].trim() : "";
        if (!days.isEmpty()) {
            if (!days.matches("\\d{1,9}") || Integer.parseInt(days) > TERMS_MAX) {
                throw refused("Line " + number + ": the terms must be a whole number of days up to " + TERMS_MAX + ".");
            }
            terms = Integer.parseInt(days);
        }
        String contact = fields.length > 3 ? fields[3].trim() : "";
        if (contact.length() > TEXT_MAX) {
            throw refused("Line " + number + ": the contact is longer than " + TEXT_MAX + " characters.");
        }
        return new Line(name, grade.isEmpty() ? null : grade, terms, contact.isEmpty() ? null : contact);
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
