package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ImportPeopleService {

    /** A pasted line, read: the person's fields as the person form takes them. */
    private record Line(int number, String name, Grade grade, Integer terms, String contact) {
    }

    private final PersonService service;

    public ImportPeopleService(PersonService service) {
        this.service = service;
    }

    /** Add a person per pasted line of name,grade,terms,contact, in order, each as the person form would.
     *  Every line is read first with the form's rules; one change: when any line is refused, nothing is
     *  added and nothing is written. Blank lines are skipped; a paste with no lines is refused, as is one
     *  repeating a name or a contact of an earlier line, compared as the form compares them. */
    @Transactional
    public List<Person> importPeople(String sheetId, String user, String text) {
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
        List<Person> added = new ArrayList<>();
        for (Line line : lines) {
            try {
                added.add(service.add(sheetId, user, line.name(), line.grade(), line.terms(), line.contact()));
            } catch (ResponseStatusException e) {
                throw new ResponseStatusException(e.getStatusCode(), "Line " + line.number() + ": " + e.getReason());
            }
        }
        return added;
    }

    /** A line of name,grade,terms,contact; the terms and contact may be blank or absent. */
    private static Line read(String raw, int number) {
        String[] fields = raw.split(",", 4);
        if (fields.length < 2) throw refused(number, "it needs at least a name and a grade");
        String name = fields[0].trim();
        if (name.isEmpty()) throw refused(number, "the name is blank");
        if (name.length() > 200) throw refused(number, "the name is longer than 200 characters");
        Grade grade;
        try {
            grade = Grade.valueOf(fields[1].trim());
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
