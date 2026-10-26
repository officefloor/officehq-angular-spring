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
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, SheetService sheets, RollupService rollup, Audit audit) {
        this.people = people;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Person add(String by, String sheetId, String name, String grade) {
        sheets.load(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String trimmed = name.strip();
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, trimmed)) {
            throw nameInUse();
        }
        Person added = save(new Person(sheetId, trimmed, grade));
        audit.recordOnCommit(record("PERSON_ADDED", by, added));
        rollup.recordChanges(by, sheetId, before);
        return added;
    }

    /** Saves an edit made from the given version of the person; refused when it has been saved since. */
    @Transactional
    public Person update(String by, String sheetId, Long id, long version, String name, String grade) {
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
        person.setGrade(grade);
        Person saved = save(person);
        audit.recordOnCommit(record("PERSON_EDITED", by, saved));
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

    private static String record(String event, String by, Person p) {
        return event + " sheet=" + p.getSheetId() + " by=" + by + " name=" + p.getName() + " grade=" + p.getGrade();
    }

    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Someone else has saved this person since it was opened.");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet.");
    }
}
