package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The people kept on a sheet, in the order they were added. A name is stored without surrounding
 * spaces and is unique on its sheet regardless of case. An edit is made from a version of the
 * person; once someone else has saved the person since, it is refused.
 */
@Service
public class PersonService {

    private static final int MAX_NAME = 100;
    private static final Set<String> GRADES = Set.of("A", "B", "C");

    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public PersonService(PersonRepository people, SheetService sheets, Audit audit) {
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's today, the date the grade each person is listed with is in force on. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        sheets.load(sheetId, user);
        String trimmed = name.trim();
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, trimmed)) {
            throw nameInUse();
        }
        Person person = people.save(new Person(sheetId, trimmed, grade));
        audit.change("PERSON_ADDED", sheetId, user, "name=" + person.getName(), "grade=" + grade);
        return person;
    }

    /** A line of an import that breaks a rule: its number (from 1) and why. */
    public record ImportError(int line, String message) {
    }

    /**
     * How many people an import added and how many existing ones it updated, or the lines that
     * stopped it (then nothing is changed).
     */
    public record ImportResult(int added, int updated, List<ImportError> errors) {
    }

    /**
     * Import people from lines of name,grade. Every line is checked first with the rules of the form;
     * a name repeated in the paste is compared like uniqueness on the sheet. If any line breaks a
     * rule nothing is changed; otherwise, in order, a name already on the sheet has its grade
     * recorded from the sheet's today and any other name is added. Blank lines are skipped.
     */
    @Transactional
    public ImportResult importPeople(String sheetId, String user, String text) {
        String[] lines = text.split("\\r?\\n", -1);
        List<ImportError> errors = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            int number = i + 1;
            int comma = line.lastIndexOf(',');
            String name = (comma < 0 ? line : line.substring(0, comma)).trim();
            String grade = comma < 0 ? "" : line.substring(comma + 1).trim();
            String error = null;
            if (name.isEmpty()) {
                error = "Enter a name.";
            } else if (name.length() > MAX_NAME) {
                error = "The name is too long.";
            } else if (!GRADES.contains(grade)) {
                error = "The grade must be A, B or C.";
            } else if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                error = "That name is repeated in the import.";
            }
            if (error != null) {
                errors.add(new ImportError(number, error));
            } else {
                rows.add(new String[] {name, grade});
            }
        }
        if (!errors.isEmpty()) {
            return new ImportResult(0, 0, errors);
        }
        LocalDate today = null;
        if (!rows.isEmpty()) {
            sheets.load(sheetId, user);
            today = sheets.today(sheetId);
        }
        int added = 0;
        int updated = 0;
        for (String[] row : rows) {
            Optional<Person> existing = people.findFirstBySheetIdAndNameIgnoreCase(sheetId, row[0]);
            if (existing.isPresent()) {
                Person person = existing.get();
                person.setGradeFrom(today, row[1]);
                person = people.saveAndFlush(person);
                audit.change("PERSON_EDITED", sheetId, user, "name=" + person.getName(), "grade=" + row[1]);
                updated++;
            } else {
                Person person = people.save(new Person(sheetId, row[0], row[1]));
                audit.change("PERSON_ADDED", sheetId, user, "name=" + person.getName(), "grade=" + row[1]);
                added++;
            }
        }
        return new ImportResult(added, updated, List.of());
    }

    /**
     * Save a person's name, and record the grade from a date (the sheet's today when none is given),
     * keeping the grades already recorded; a grade already recorded from that date is replaced.
     */
    @Transactional
    public Person update(String sheetId, String user, Long personId, long version, String name, String grade,
            LocalDate gradeFrom) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (person.getVersion() != version) {
            throw changedMeanwhile();
        }
        String trimmed = name.trim();
        if (people.existsBySheetIdAndNameIgnoreCaseAndIdNot(sheetId, trimmed, personId)) {
            throw nameInUse();
        }
        person.setName(trimmed);
        person.setGradeFrom(gradeFrom != null ? gradeFrom : sheets.today(sheetId), grade);
        try {
            person = people.saveAndFlush(person);
        } catch (OptimisticLockingFailureException e) {
            throw changedMeanwhile();
        }
        audit.change("PERSON_EDITED", sheetId, user, "name=" + person.getName(), "grade=" + grade);
        return person;
    }

    /**
     * Retire a person from a date (the sheet's today when none is given). A person already retired is
     * refused (409); claims dated on or before the date stay allowed.
     */
    @Transactional
    public Person retire(String sheetId, String user, Long personId, LocalDate from) {
        // Lock the sheet, so a claim added at the same moment sees the retirement.
        sheets.lock(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (person.getRetiredFrom() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Person already retired");
        }
        person.setRetiredFrom(from != null ? from : sheets.today(sheetId));
        person = people.saveAndFlush(person);
        audit.change("PERSON_RETIRED", sheetId, user, "name=" + person.getName(), "from=" + person.getRetiredFrom());
        return person;
    }

    /**
     * Put a person on hold from a date (the sheet's today when none is given) to an optional to-date,
     * both included; a person may have several holds. A to-date before the from-date is refused (422).
     */
    @Transactional
    public Person hold(String sheetId, String user, Long personId, LocalDate from, LocalDate to) {
        // Lock the sheet, so a period closed at the same moment sees the hold.
        sheets.lock(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate start = from != null ? from : sheets.today(sheetId);
        if (to != null && to.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "The hold ends before it starts");
        }
        person.addHold(start, to);
        person = people.saveAndFlush(person);
        audit.change("PERSON_HELD", sheetId, user, "name=" + person.getName(), "from=" + start);
        return person;
    }

    /**
     * Name another person on the same sheet as one of this person's patrons. Naming themselves is
     * refused (422), and so is naming someone already a patron (409).
     */
    @Transactional
    public Person addPatron(String sheetId, String user, Long personId, Long patronId) {
        sheets.lock(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patron"));
        if (patron.getId().equals(person.getId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "A person cannot be their own patron");
        }
        if (!person.getPatronIds().add(patron.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already a patron");
        }
        person = people.saveAndFlush(person);
        audit.change("PATRON_ADDED", sheetId, user, "name=" + person.getName(), "patron=" + patron.getName());
        return person;
    }

    private static ResponseStatusException changedMeanwhile() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Person changed since it was loaded");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Name already in use");
    }
}
