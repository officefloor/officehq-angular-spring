package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
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
    private final PatronRepository patrons;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, PatronRepository patrons, SheetService sheets, RollupService rollup, Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The sheet's today, which the grade a person is listed with is in force on. */
    @Transactional
    public LocalDate today(String sheetId) {
        return sheets.load(sheetId).getToday();
    }

    /** Adds the person; the grade they are given first applies from the beginning of time. */
    @Transactional
    public Person add(String by, String sheetId, String name, String grade) {
        sheets.load(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String trimmed = name.strip();
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, trimmed)) {
            throw nameInUse();
        }
        Person added = save(new Person(sheetId, trimmed, grade));
        audit.recordOnCommit(record("PERSON_ADDED", by, added, today(sheetId)));
        rollup.recordChanges(by, sheetId, before);
        return added;
    }

    /**
     * Saves an edit made from the given version of the person; refused when it has been saved since. The grade is
     * recorded from the from-date (missing: the sheet's today), keeping the ones already recorded, except that an
     * edit leaving the grade in force today as it is, with no from-date, records nothing.
     */
    @Transactional
    public Person update(String by, String sheetId, Long id, long version, String name, String grade,
            LocalDate gradeFrom) {
        LocalDate today = today(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (person.getVersion() != version) {
            throw stale();
        }
        String trimmed = name.strip();
        if (people.existsBySheetIdAndNameIgnoreCaseAndIdNot(sheetId, trimmed, id)) {
            throw nameInUse();
        }
        person.setName(trimmed);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.recordGrade(grade, gradeFrom != null ? gradeFrom : today);
        }
        Person saved = save(person);
        audit.recordOnCommit(record("PERSON_EDITED", by, saved, today));
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /** Retires the person from the date; a missing date takes the sheet's today. Their claims keep counting. */
    @Transactional
    public Person retire(String by, String sheetId, Long id, LocalDate from) {
        LocalDate on = from != null ? from : sheets.load(sheetId).getToday();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        person.setRetiredFrom(on);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + by + " name=" + saved.getName()
                + " from=" + on);
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /** Puts the person on hold, or releases them when held. While held, their open claims count in no live total. */
    @Transactional
    public Person toggleHold(String by, String sheetId, Long id) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        person.setHeld(!person.isHeld());
        Person saved = save(person);
        audit.recordOnCommit((saved.isHeld() ? "PERSON_HELD" : "PERSON_RELEASED") + " sheet=" + sheetId + " by=" + by
                + " name=" + saved.getName());
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Patron> patrons(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Names another person on the sheet as the person's patron; never themselves, nor anyone twice. */
    @Transactional
    public Patron addPatron(String by, String sheetId, Long id, Long patronId) {
        Person person = people.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patron."));
        if (person.getId().equals(patron.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A person cannot be their own patron.");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw patronNamed();
        }
        Patron added;
        try {
            added = patrons.saveAndFlush(new Patron(sheetId, person.getId(), patron.getId()));
        } catch (DataIntegrityViolationException e) {
            throw patronNamed();
        }
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + by + " name=" + person.getName()
                + " patron=" + patron.getName());
        return added;
    }

    /** Saves the person; the store's unique index refuses a name another request took meanwhile. */
    private Person save(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        } catch (ObjectOptimisticLockingFailureException e) {
            // another request saved the person between this one reading and writing it
            throw stale();
        }
    }

    /** The audit line about a person, with the grade in force on the sheet's today. */
    private static String record(String event, String by, Person p, LocalDate today) {
        return event + " sheet=" + p.getSheetId() + " by=" + by + " name=" + p.getName() + " grade="
                + p.gradeOn(today);
    }

    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Someone else has saved this person since it was opened.");
    }

    private static ResponseStatusException patronNamed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That person is already one of their patrons.");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet.");
    }
}
