package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the people on a sheet. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final SheetService sheetService;

    public PersonService(PersonRepository people, SheetRepository sheets, Audit audit,
            RollupService rollup, SheetService sheetService) {
        this.sheetService = sheetService;
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        if (people.existsBySheetIdAndNameKey(sheetId, Person.key(name))) {
            throw new PersonNameTakenException(sheetId, name.trim());
        }
        Person saved = save(new Person(sheetId, name, grade));
        audit.recordOnCommit(record("PERSON_ADDED", user, saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    @Transactional
    public Person update(String sheetId, String user, Long id, Long version, String name, String grade) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        if (version != null && version != person.getVersion()) {
            throw new PersonVersionConflictException(sheetId, id);
        }
        if (people.existsBySheetIdAndNameKeyAndIdNot(sheetId, Person.key(name), id)) {
            throw new PersonNameTakenException(sheetId, name.trim());
        }
        person.setName(name);
        person.setGrade(grade);
        Person saved = save(person);
        audit.recordOnCommit(record("PERSON_EDITED", user, saved));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** Retire the person from the date, or from the sheet's today when none is given. */
    @Transactional
    public Person retire(String sheetId, String user, Long id, LocalDate from) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        person.setRetiredFrom(from != null ? from : sheetService.today(sheetId));
        Person saved = save(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " from=" + saved.getRetiredFrom());
        return saved;
    }

    /** Put the person on hold, or take them off it when they are held. */
    @Transactional
    public Person toggleHold(String sheetId, String user, Long id) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        person.setHeld(!person.isHeld());
        Person saved = save(person);
        audit.recordOnCommit((saved.isHeld() ? "PERSON_HELD" : "PERSON_RELEASED") + " sheet=" + sheetId
                + " by=" + user + " name=" + saved.getName());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    private static String record(String event, String user, Person person) {
        return event + " sheet=" + person.getSheetId() + " by=" + user
                + " name=" + person.getName() + " grade=" + person.getGrade();
    }

    private Person save(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            // Another user took the name at the same moment: the unique index refused it.
            throw new PersonNameTakenException(person.getSheetId(), person.getName());
        } catch (ObjectOptimisticLockingFailureException e) {
            // Another user saved the person at the same moment.
            throw new PersonVersionConflictException(person.getSheetId(), person.getId());
        }
    }
}
