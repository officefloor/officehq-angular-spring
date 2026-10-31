package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The people kept on a sheet. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;
    private final Rollup rollup;

    public PersonService(PersonRepository people, SheetService sheets, Audit audit, Rollup rollup) {
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** Everyone on the sheet, in the order they were added. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a person; the name is stored trimmed and must not already be in use on the sheet. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        sheets.load(sheetId, user);
        String trimmed = name.trim();
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, trimmed)) {
            throw nameInUse();
        }
        Rollup.Figures before = rollup.of(sheetId);
        Person added = save(new Person(sheetId, trimmed, grade));
        audit.recordChangeOnCommit(sheetId, user, entry("PERSON_ADDED", user, added, grade));
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    /** The sheet's today, or null for a sheet never loaded. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
    }

    /**
     * Edit a person, as loaded at {@code version}: refused when someone has saved them since. The grade is
     * recorded from {@code gradeFrom} (null: the sheet's today), keeping the grades already recorded from
     * other dates; a save leaving the grade as in force on the sheet's today, with no from-date, records none.
     */
    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, String grade,
            LocalDate gradeFrom) {
        LocalDate today = sheets.load(sheetId, user).getToday();
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (person.getVersion() != version) {
            throw changedSince();
        }
        String trimmed = name.trim();
        if (people.existsBySheetIdAndNameIgnoreCaseAndIdNot(sheetId, trimmed, id)) {
            throw nameInUse();
        }
        Rollup.Figures before = rollup.of(sheetId);
        person.setName(trimmed);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.setGradeFrom(gradeFrom != null ? gradeFrom : today, grade);
        }
        Person edited = save(person);
        audit.recordChangeOnCommit(sheetId, user, entry("PERSON_EDITED", user, edited, edited.gradeOn(today)));
        rollup.recordChanges(sheetId, user, before);
        return edited;
    }

    /** Retire a person from a date; a missing date takes the sheet's today. They count as retired at once. */
    @Transactional
    public Person retire(String sheetId, String user, long id, LocalDate from) {
        LocalDate today = sheets.load(sheetId, user).getToday();
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        person.setRetiredFrom(from != null ? from : today);
        Person retired = save(person);
        audit.recordChangeOnCommit(sheetId, user, "PERSON_RETIRED sheet=" + sheetId + " by=" + user
                + " name=" + retired.getName() + " from=" + retired.getRetiredFrom());
        return retired;
    }

    /** Put the person on hold from {@code from} (null: the sheet's today) to {@code to} (null: no end), alongside
     *  any holds they have already; the live totals move with it. A to-date before the from-date is refused. */
    @Transactional
    public Person hold(String sheetId, String user, long id, LocalDate from, LocalDate to) {
        LocalDate today = sheets.load(sheetId, user).getToday();
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        LocalDate start = from != null ? from : today;
        if (to != null && to.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A hold cannot end before it starts");
        }
        Rollup.Figures before = rollup.of(sheetId);
        person.addHold(new Hold(start, to));
        Person saved = save(person);
        audit.recordChangeOnCommit(sheetId, user, "PERSON_HELD sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " from=" + start);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    private static String entry(String event, String user, Person person, String grade) {
        return event + " sheet=" + person.getSheetId() + " by=" + user
                + " name=" + person.getName() + " grade=" + grade;
    }

    private Person save(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException raced) {
            // Another user took the same name at the same moment: theirs stands.
            throw nameInUse();
        } catch (ObjectOptimisticLockingFailureException raced) {
            // Another user saved the same person at the same moment: theirs stands.
            throw changedSince();
        }
    }

    private static ResponseStatusException changedSince() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Someone else has saved this person since it was opened");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet");
    }
}
