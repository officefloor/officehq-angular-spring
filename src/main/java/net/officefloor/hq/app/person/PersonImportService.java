package net.officefloor.hq.app.person;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.officefloor.hq.app.rollup.RollupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds a person per pasted line of name,grade,terms,contact (terms and contact may be absent or
 *  blank), each as the person form would. Every line is checked first; the whole paste is one change,
 *  so one refused line imports nothing. */
@Service
public class PersonImportService {

    /** The most a name or contact holds, as on the person form. */
    private static final int TEXT_MAX = 200;

    /** The most days a person's own terms run to, as on the person form. */
    private static final int TERMS_MAX = 36500;

    /** A pasted line, read: the person's fields (null terms: none; null contact: none). */
    private record Line(String name, String grade, Integer terms, String contact) {
    }

    private final PersonService people;
    private final RollupService rollups;

    public PersonImportService(PersonService people, RollupService rollups) {
        this.people = people;
        this.rollups = rollups;
    }

    /** Add the pasted people in the order of their lines; blank lines are skipped. A name or contact
     *  repeated within the paste, or already in use as the form judges, refuses the whole paste. */
    @Transactional
    public List<Person> importPeople(String sheetId, String user, String text) {
        List<Line> lines = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> contacts = new HashSet<>();
        int at = 0;
        for (String raw : text.split("\\r?\\n")) {
            at++;
            if (raw.isBlank()) {
                continue;
            }
            Line line = read(at, raw);
            if (!names.add(line.name().toLowerCase(Locale.ROOT))) {
                throw new ImportRefusedException("Line " + at + ": the name " + line.name() + " is repeated");
            }
            if (line.contact() != null && !contacts.add(line.contact())) {
                throw new ImportRefusedException("Line " + at + ": the contact " + line.contact() + " is repeated");
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            throw new ImportRefusedException("Nothing to import");
        }
        // Each person is added as the form adds them, so a name or contact already in use on the sheet
        // (or its tree) refuses the paste, and the transaction takes back the people added before it.
        return lines.stream()
                .map(l -> rollups.track(sheetId, user,
                        () -> people.add(sheetId, user, l.name(), l.grade(), l.terms(), l.contact())))
                .toList();
    }

    /** One line as the person form reads it: a name that is not blank, a grade of A, B or C, terms of
     *  a whole number of days (blank: none) and a contact (blank: none), each trimmed. */
    private static Line read(int at, String raw) {
        String[] fields = raw.split(",", -1);
        if (fields.length < 2 || fields.length > 4) {
            throw new ImportRefusedException("Line " + at + " needs name,grade,terms,contact");
        }
        String name = fields[0].trim();
        if (name.isEmpty() || name.length() > TEXT_MAX) {
            throw new ImportRefusedException("Line " + at + ": the name must not be blank or too long");
        }
        String grade = fields[1].trim();
        if (!grade.matches("[ABC]")) {
            throw new ImportRefusedException("Line " + at + ": the grade must be A, B or C");
        }
        String termsText = fields.length > 2 ? fields[2].trim() : "";
        Integer terms = null;
        if (!termsText.isEmpty()) {
            if (!termsText.matches("\\d{1,5}") || Integer.parseInt(termsText) > TERMS_MAX) {
                throw new ImportRefusedException("Line " + at + ": the terms must be a whole number of days");
            }
            terms = Integer.parseInt(termsText);
        }
        String contact = fields.length > 3 ? fields[3].trim().toLowerCase(Locale.ROOT) : "";
        if (contact.length() > TEXT_MAX) {
            throw new ImportRefusedException("Line " + at + ": the contact is too long");
        }
        return new Line(name, grade, terms, contact.isEmpty() ? null : contact);
    }
}
