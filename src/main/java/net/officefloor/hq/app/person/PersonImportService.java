package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The import box: lines of name,grade,terms,contact, each adding a person as the person form
 *  would; the terms and contact may be absent or blank. */
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

    private final PersonRepository people;
    private final PersonService service;

    public PersonImportService(PersonRepository people, PersonService service) {
        this.people = people;
        this.service = service;
    }

    /** Add a person per line, in order, checking every line first with the person form's rules; one
     *  change, so when any line is refused no one is added and nothing is recorded. Blank lines are
     *  skipped; an import with no lines is refused, and so is one repeating a name or contact within
     *  it. Returns the people added, in order. */
    @Transactional
    public List<Person> importPeople(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        int n = 0;
        for (String raw : text.split("\\R")) {
            n++;
            if (raw.isBlank()) {
                continue;
            }
            Line line = parse(n, raw);
            if (!names.add(line.name().toLowerCase(Locale.ROOT))) {
                throw refused(n, "the name \"" + line.name() + "\" is repeated in the import");
            }
            if (line.contact() != null && !contacts.add(line.contact())) {
                throw refused(n, "the contact \"" + line.contact() + "\" is repeated in the import");
            }
            if (people.existsBySheetIdAndNameIgnoreCase(sheetId, line.name())
                    || people.existsAlias(sheetId, line.name())) {
                throw refused(n, "the name \"" + line.name() + "\" is already in use on this sheet");
            }
            if (line.contact() != null && people.existsBySheetIdAndContact(sheetId, line.contact())) {
                throw refused(n, "the contact \"" + line.contact() + "\" is already held on this sheet");
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste at least one line");
        }
        List<Person> added = new ArrayList<>();
        for (Line line : lines) {
            added.add(service.add(sheetId, user, line.name(), line.grade(), line.terms(), line.contact()));
        }
        return added;
    }

    /** A line of name,grade,terms,contact: the last two may be absent or blank. */
    private static Line parse(int n, String raw) {
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
        String grade = fields[1].trim();
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
