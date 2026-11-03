package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
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
 * name is unique on the sheet, compared without regard to case (names arrive trimmed). A person can
 * be retired from a date (the sheet's today when none is given); they count as retired at once.
 */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;

    public PersonService(PersonRepository people, SheetService sheets, Audit audit, RollupService rollup) {
        this.people = people;
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
        sheets.load(sheetId, user);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, name)) {
            throw nameInUse();
        }
        Person added = save(new Person(sheetId, name, grade));
        audit.recordOnCommit(record("PERSON_ADDED", sheetId, user, added));
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, String grade) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (person.getVersion() != version) {
            throw stale();
        }
        if (people.existsBySheetIdAndNameIgnoreCaseAndIdNot(sheetId, name, id)) {
            throw nameInUse();
        }
        person.setName(name);
        person.setGrade(grade);
        Person saved = save(person);
        audit.recordOnCommit(record("PERSON_EDITED", sheetId, user, saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** Retire the person from a date, or from the sheet's today when none is given. */
    @Transactional
    public Person retire(String sheetId, String user, long id, LocalDate date) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        LocalDate from = date != null ? date : sheets.today(sheetId);
        person.setRetiredFrom(from);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user + " name=" + saved.getName() + " from="
                + from);
        return saved;
    }

    private static String record(String event, String sheetId, String user, Person person) {
        return event + " sheet=" + sheetId + " by=" + user + " name=" + person.getName() + " grade="
                + person.getGrade();
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
