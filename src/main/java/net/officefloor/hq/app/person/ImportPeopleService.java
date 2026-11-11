package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds a person per pasted line of name,grade,terms,contact, in order, each as the person form would (the terms and
 * the contact may be absent or blank). Every line is checked first with the person form's rules, and a name or a
 * contact repeated within the paste is refused too. The whole is one change: if any line is refused, nothing is added.
 */
@Service
public class ImportPeopleService {

    private static final int MAX_NAME = 200;

    private static final int MAX_CONTACT = 200;

    private record Line(int number, String name, String grade, Integer terms, String contact) {
    }

    private final PersonService people;

    public ImportPeopleService(PersonService people) {
        this.people = people;
    }

    /** How many people were added. */
    @Transactional
    public int importPeople(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        String[] rows = text.split("\\r?\\n");
        for (int i = 0; i < rows.length; i++) {
            if (!rows[i].isBlank()) {
                lines.add(parse(i + 1, rows[i]));
            }
        }
        if (lines.isEmpty()) {
            throw new ImportPeopleException("Paste at least one line of name,grade,terms,contact");
        }
        Set<String> names = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        for (Line line : lines) {
            String key = Person.key(line.name());
            if (!names.add(key) || people.findByNameOrAlias(sheetId, line.name()).isPresent()
                    || people.usedInTree(sheetId, key, null)) {
                throw new ImportPeopleException(line.number(), "the name " + line.name().trim() + " is already in use");
            }
            String contactKey = Person.contactKey(line.contact());
            if (contactKey != null && !contacts.add(contactKey)) {
                throw new ImportPeopleException(line.number(), "the contact " + contactKey + " is already in use");
            }
        }
        // Each person is checked again as the person form's are; a refused one rolls back those added before it.
        for (Line line : lines) {
            people.add(sheetId, user, line.name(), line.grade(), line.terms(), line.contact());
        }
        return lines.size();
    }

    private static Line parse(int number, String row) {
        String[] fields = row.split(",", -1);
        if (fields.length < 2 || fields.length > 4) {
            throw new ImportPeopleException(number, "expected name,grade,terms,contact");
        }
        String name = fields[0];
        if (name.isBlank()) {
            throw new ImportPeopleException(number, "the name is blank");
        }
        if (name.length() > MAX_NAME) {
            throw new ImportPeopleException(number, "the name is too long");
        }
        String grade = fields[1].trim();
        if (!grade.matches("[ABC]")) {
            throw new ImportPeopleException(number, "the grade is not A, B or C");
        }
        String terms = fields.length > 2 ? fields[2] : "";
        if (!terms.matches("\\s*(\\d{1,5})?\\s*")) {
            throw new ImportPeopleException(number, "the terms are not a whole number of days");
        }
        String contact = fields.length > 3 ? fields[3] : "";
        if (contact.length() > MAX_CONTACT) {
            throw new ImportPeopleException(number, "the contact is too long");
        }
        return new Line(number, name, grade, terms.isBlank() ? null : Integer.valueOf(terms.trim()), contact);
    }
}
