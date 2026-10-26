package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
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

    /** Adds the person with no terms of their own. */
    @Transactional
    public Person add(String by, String sheetId, String name, String grade) {
        return add(by, sheetId, name, grade, null);
    }

    /**
     * Adds the person with their own terms (null: none); the grade they are given first applies from the beginning
     * of time.
     */
    @Transactional
    public Person add(String by, String sheetId, String name, String grade, Integer terms) {
        LocalDate today = sheets.load(sheetId).getToday();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String trimmed = name.strip();
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, trimmed)) {
            throw nameInUse();
        }
        Person person = new Person(sheetId, trimmed, grade, today);
        person.setTerms(terms);
        Person added = save(person);
        audit.recordOnCommit(record("PERSON_ADDED", by, added, today(sheetId))
                + (terms != null ? " terms=" + terms : ""));
        rollup.recordChanges(by, sheetId, before);
        return added;
    }

    /**
     * Saves an edit made from the given version of the person; refused when it has been saved since. The grade is
     * recorded from the from-date (missing: the sheet's today), keeping the ones already recorded, except that an
     * edit leaving the grade in force today as it is, with no from-date, records nothing. The person's own terms are
     * set to the given ones (null: none).
     */
    @Transactional
    public Person update(String by, String sheetId, Long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms) {
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
            person.recordGrade(grade, gradeFrom != null ? gradeFrom : today, today);
        }
        boolean termsChanged = !Objects.equals(terms, person.getTerms());
        person.setTerms(terms);
        Person saved = save(person);
        audit.recordOnCommit(record("PERSON_EDITED", by, saved, today)
                + (termsChanged ? " terms=" + (terms != null ? terms : "") : ""));
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /** Retires the person from the date; a missing date takes the sheet's today. Their claims keep counting. */
    @Transactional
    public Person retire(String by, String sheetId, Long id, LocalDate from) {
        LocalDate today = sheets.load(sheetId).getToday();
        LocalDate on = from != null ? from : today;
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        person.setRetiredFrom(on, today);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + by + " name=" + saved.getName()
                + " from=" + on);
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Puts a hold on the person from the date (missing: the sheet's today) to the to-date (missing: with no end); the
     * holds already put are kept, each ending on its to-date. Their open claims dated within a hold, not bound by a
     * statement, count in no live total. Refused when the to-date is before the from-date.
     */
    @Transactional
    public Person hold(String by, String sheetId, Long id, LocalDate from, LocalDate to) {
        LocalDate today = sheets.load(sheetId).getToday();
        LocalDate on = from != null ? from : today;
        if (to != null && to.isBefore(on)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A hold cannot end before it starts.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        person.addHold(on, to, today);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + by + " name=" + saved.getName()
                + " from=" + on);
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
