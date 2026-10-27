package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Adds a person for each pasted line of name,grade,terms,contact (the last two may be absent or blank), all of them
 * or none.
 */
@Service
public class PersonImportService {

    /** A pasted line read into the person form's fields. */
    private record Line(String name, String grade, Integer terms, String contact) {
    }

    private final PersonService people;
    private final PersonRepository repository;

    public PersonImportService(PersonService people, PersonRepository repository) {
        this.people = people;
        this.repository = repository;
    }

    /**
     * Reads every line first by the person form's rules, a name or contact repeated within the paste refused too,
     * then adds a person per line in order, each as the form would. One change: any line refused refuses them all,
     * and nothing is stored or audited.
     */
    @Transactional
    public List<Person> importPeople(String by, String sheetId, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        int n = 0;
        for (String raw : text.split("\\r?\\n")) {
            n++;
            if (raw.isBlank()) {
                continue;
            }
            Line line = read(n, raw);
            if (!names.add(line.name().toLowerCase(Locale.ROOT))) {
                throw refused("Line " + n + " repeats the name " + line.name() + ".");
            }
            if (line.contact() != null && !contacts.add(line.contact())) {
                throw refused("Line " + n + " repeats the contact " + line.contact() + ".");
            }
            if (people.nameOrAliasInUse(sheetId, line.name())) {
                throw refused("Line " + n + ": the name " + line.name() + " is already in use on this sheet.");
            }
            if (line.contact() != null && repository.existsBySheetIdAndContact(sheetId, line.contact())) {
                throw refused("Line " + n + ": the contact " + line.contact() + " is already in use on this sheet.");
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            throw refused("Paste at least one line of name,grade,terms,contact.");
        }
        List<Person> added = new ArrayList<>();
        for (Line l : lines) {
            added.add(people.add(by, sheetId, l.name(), l.grade(), l.terms(), l.contact()));
        }
        return added;
    }

    private Line read(int n, String raw) {
        String[] f = raw.split(",", 4);
        if (f.length < 2) {
            throw refused("Line " + n + " needs at least a name and a grade.");
        }
        String name = f[0].strip();
        if (name.isEmpty()) {
            throw refused("Line " + n + " has no name.");
        }
        if (name.length() > 200) {
            throw refused("Line " + n + " has a name longer than 200 characters.");
        }
        String grade = f[1].strip();
        if (!grade.matches("[ABC]")) {
            throw refused("Line " + n + " has a grade that is not A, B or C.");
        }
        Integer terms = null;
        String termsText = f.length > 2 ? f[2].strip() : "";
        if (!termsText.isEmpty()) {
            if (!termsText.matches("\\d{1,5}")) {
                throw refused("Line " + n + " has terms that are not a whole number of days.");
            }
            terms = Integer.valueOf(termsText);
        }
        String contact = f.length > 3 ? f[3].strip() : "";
        if (contact.length() > 200) {
            throw refused("Line " + n + " has a contact longer than 200 characters.");
        }
        return new Line(name, grade, terms, contact.isEmpty() ? null : contact.toLowerCase(Locale.ROOT));
    }

    private static ResponseStatusException refused(String reason) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, reason);
    }
}
