package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;
    private final Rollup rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, SheetService sheets, Rollup rollup, Audit audit) {
        this.people = people;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's people in the order they were added. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a person to the sheet. */
    @Transactional
    public Person add(String sheetId, String user, String name, Grade grade) {
        sheets.load(sheetId);
        if (people.existsBySheetIdAndNameKey(sheetId, Person.keyOf(name))) {
            throw nameInUse();
        }
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        Person person = save(new Person(sheetId, name, grade));
        recordChange("PERSON_ADDED", user, person);
        rollup.recordChanges(user, before);
        return person;
    }

    /** Change a person's name and grade, refused when someone has saved the person since the editor
     *  loaded this version of them. */
    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, Grade grade) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (person.getVersion() != version) {
            throw changedMeanwhile();
        }
        if (people.existsBySheetIdAndNameKeyAndIdNot(sheetId, Person.keyOf(name), id)) {
            throw nameInUse();
        }
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        person.setName(name);
        person.setGrade(grade);
        Person saved = save(person);
        recordChange("PERSON_EDITED", user, saved);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Retire a person from a date, the sheet's today when none is given. Their claims keep counting;
     *  claims dated after the date can no longer be added to them. */
    @Transactional
    public Person retire(String sheetId, String user, long id, LocalDate from) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        LocalDate dated = from != null ? from : sheets.today(sheetId);
        person.setRetiredFrom(dated);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " from=" + dated);
        return saved;
    }

    private void recordChange(String event, String user, Person person) {
        audit.recordOnCommit(event + " sheet=" + person.getSheetId() + " by=" + user
                + " name=" + person.getName() + " grade=" + person.getGrade());
    }

    private Person save(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (OptimisticLockingFailureException raced) {
            // Another user saved the same person at the same moment.
            throw changedMeanwhile();
        } catch (DataIntegrityViolationException raced) {
            // Another user took the same name at the same moment.
            throw nameInUse();
        }
    }

    private static ResponseStatusException changedMeanwhile() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Someone else has saved this person since it was opened.");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on the sheet.");
    }
}
