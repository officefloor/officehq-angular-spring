package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The people on a sheet: listed in the order they were added, added and edited by name and grade. A
 * person's grade is dated: the first applies from the beginning of time, and each edit records the
 * grade from its from-date (the sheet's today when none is given), keeping those already recorded;
 * an edit leaving the grade in force on the sheet's today as it is, with no from-date, records none.
 * A
 * name is unique on the sheet, compared without regard to case (names arrive trimmed). A person can
 * be retired from a date (the sheet's today when none is given); they count as retired at once. A
 * person can be put on hold over a span of dates, and may have several holds over time. A person can name other people on the sheet as
 * their patrons, one at a time: not themselves, and not the same person twice. A user can watch a
 * person, and stop watching them; each user's watching is their own.
 */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final PatronRepository patrons;
    private final RoleService roles;
    private final WatchRepository watches;

    public PersonService(PersonRepository people, SheetService sheets, Audit audit, RollupService rollup,
            PatronRepository patrons, RoleService roles, WatchRepository watches) {
        this.roles = roles;
        this.watches = watches;
        this.people = people;
        this.patrons = patrons;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, name)) {
            throw nameInUse();
        }
        Person added = save(new Person(sheetId, name, grade));
        audit.recordOnCommit(record("PERSON_ADDED", sheetId, user, added, sheets.today(sheetId)));
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, String grade,
            LocalDate gradeFrom) {
        roles.refuseViewer(sheetId, user);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (person.getVersion() != version) {
            throw stale();
        }
        if (people.existsBySheetIdAndNameIgnoreCaseAndIdNot(sheetId, name, id)) {
            throw nameInUse();
        }
        LocalDate today = sheets.today(sheetId);
        person.setName(name);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.setGradeFrom(gradeFrom != null ? gradeFrom : today, grade);
        }
        Person saved = save(person);
        audit.recordOnCommit(record("PERSON_EDITED", sheetId, user, saved, today));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** Retire the person from a date, or from the sheet's today when none is given. */
    @Transactional
    public Person retire(String sheetId, String user, long id, LocalDate date) {
        roles.refuseViewer(sheetId, user);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        LocalDate from = date != null ? date : sheets.today(sheetId);
        person.setRetiredFrom(from);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user + " name=" + saved.getName() + " from="
                + from);
        return saved;
    }

    /**
     * Put the person on hold from a date (the sheet's today when none is given) to an optional
     * to-date; refused (400) when the to-date is before the from-date. Their OPEN claims dated within
     * the hold leave every live total, so the figures that move are recorded.
     */
    @Transactional
    public Person hold(String sheetId, String user, long id, LocalDate fromDate, LocalDate toDate) {
        roles.refuseViewer(sheetId, user);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.lockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        LocalDate from = fromDate != null ? fromDate : sheets.today(sheetId);
        if (toDate != null && toDate.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A hold cannot end before it starts.");
        }
        person.addHold(from, toDate);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user + " name=" + saved.getName() + " from="
                + from);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Patron> patrons(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /**
     * Name another person on the sheet as the person's patron; refused (409) when it is the person
     * themselves or already one of their patrons.
     */
    @Transactional
    public Patron addPatron(String sheetId, String user, long id, long patronId) {
        roles.refuseViewer(sheetId, user);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patron on this sheet."));
        if (person.getId().equals(patron.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be their own patron.");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw patronTaken();
        }
        Patron added;
        try {
            added = patrons.saveAndFlush(new Patron(sheetId, person.getId(), patron.getId()));
        } catch (DataIntegrityViolationException e) {
            throw patronTaken();
        }
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " patron=" + patron.getName());
        return added;
    }

    /** The ids of the people the user watches on the sheet. */
    @Transactional(readOnly = true)
    public List<Long> watched(String sheetId, String user) {
        return watches.findBySheetIdAndUserName(sheetId, user).stream().map(Watch::getPersonId).toList();
    }

    /**
     * Start ({@code on}) or stop watching the person as the user; already so, nothing changes and
     * nothing is recorded.
     */
    @Transactional
    public void watch(String sheetId, String user, long id, boolean on) {
        sheets.refuseIfArchived(sheetId);
        Person person = people.lockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        var existing = watches.findByPersonIdAndUserName(person.getId(), user);
        if (on == existing.isPresent()) {
            return;
        }
        if (on) {
            watches.saveAndFlush(new Watch(sheetId, person.getId(), user));
        } else {
            watches.delete(existing.get());
            watches.flush();
        }
        audit.recordOnCommit((on ? "PERSON_WATCHED" : "PERSON_UNWATCHED") + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName());
    }

    private static ResponseStatusException patronTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That person is already a patron.");
    }

    /** A person's record, with the grade in force on the sheet's today. */
    private static String record(String event, String sheetId, String user, Person person, LocalDate today) {
        return event + " sheet=" + sheetId + " by=" + user + " name=" + person.getName() + " grade="
                + person.gradeOn(today);
    }

    /**
     * Save, refusing a name another user took on the sheet since it was checked, and an edit another
     * user saved over since the person was read.
     */
    private Person save(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            throw nameInUse();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw stale();
        }
    }

    /** The edit was made from a version of the person that another user has since saved over. */
    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Someone else has saved this person since it was opened.");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet.");
    }
}
