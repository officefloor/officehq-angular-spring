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
 *  blank), each as the person form would; a line whose name matches a person on the sheet (by name or
 *  alias, as names are compared) updates them as their editor would instead. Every line is checked
 *  first; the whole paste is one change, so one refused line imports nothing. */
@Service
public class PersonImportService {

    /** The most a name or contact holds, as on the person form. */
    private static final int TEXT_MAX = 200;

    /** The most days a person's own terms run to, as on the person form. */
    private static final int TERMS_MAX = 36500;

    /** A pasted line, read: the person's fields (null terms: none; null contact: none). */
    private record Line(String name, String grade, Integer terms, String contact) {
    }

    /** What an import did: the people it added and those it updated, each in the order of their lines. */
    public record Result(List<Person> added, List<Person> updated) {
    }

    private final PersonService people;
    private final PersonRepository repository;
    private final RollupService rollups;

    public PersonImportService(PersonService people, PersonRepository repository, RollupService rollups) {
        this.people = people;
        this.repository = repository;
        this.rollups = rollups;
    }

    /** Add or update the pasted people in the order of their lines; blank lines are skipped. A name or
     *  contact repeated within the paste, two lines for the same person, or a name or contact already in
     *  use as the form judges, refuses the whole paste. */
    @Transactional
    public Result importPeople(String sheetId, String user, String text) {
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
        // Each person is added or updated as the form does it, so a name or contact already in use on the
        // sheet (or its tree) refuses the paste, and the transaction takes back the changes before it.
        List<Person> added = new ArrayList<>();
        List<Person> updated = new ArrayList<>();
        Set<Long> matched = new HashSet<>();
        at = 0;
        for (Line l : lines) {
            at++;
            Person match = repository.findByName(sheetId, l.name()).orElse(null);
            if (match == null) {
                added.add(rollups.track(sheetId, user,
                        () -> people.add(sheetId, user, l.name(), l.grade(), l.terms(), l.contact())));
                continue;
            }
            if (!matched.add(match.getId())) {
                throw new ImportRefusedException("Line " + at + ": " + match.getName() + " is repeated");
            }
            // The grade is given from the sheet's today; terms and contact are kept unless the line gives them.
            Integer terms = l.terms() != null ? l.terms() : match.getTerms();
            String contact = l.contact() != null ? l.contact() : match.getContact();
            updated.add(rollups.track(sheetId, user, () -> people.update(sheetId, user, match.getId(),
                    match.getVersion(), match.getName(), l.grade(), null, terms, contact)));
        }
        return new Result(added, updated);
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
