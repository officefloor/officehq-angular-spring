package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final PersonPatronRepository patrons;
    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final SheetService sheetService;

    public PersonService(PersonRepository people, PersonPatronRepository patrons, SheetRepository sheets,
            Audit audit, RollupService rollup, SheetService sheetService) {
        this.sheetService = sheetService;
        this.people = people;
        this.patrons = patrons;
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
        audit.recordOnCommit(record("PERSON_ADDED", user, saved, grade));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    @Transactional
    /**
     * Save the person. The grade is recorded from the from-date (null: the sheet's today), keeping the grades
     * already recorded; a save leaving the grade as it is in force today, with no from-date, records none.
     */
    public Person update(String sheetId, String user, Long id, Long version, String name, String grade,
            LocalDate gradeFrom) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        if (version != null && version != person.getVersion()) {
            throw new PersonVersionConflictException(sheetId, id);
        }
        if (people.existsBySheetIdAndNameKeyAndIdNot(sheetId, Person.key(name), id)) {
            throw new PersonNameTakenException(sheetId, name.trim());
        }
        LocalDate today = sheetService.today(sheetId);
        person.setName(name);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.setGrade(grade, gradeFrom != null ? gradeFrom : today);
        }
        Person saved = save(person);
        audit.recordOnCommit(record("PERSON_EDITED", user, saved, saved.gradeOn(today)));
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

    /** The ids of each person's patrons, by person id, in the order they were named. */
    public Map<Long, List<Long>> patronIds(String sheetId) {
        Map<Long, List<Long>> byPerson = new HashMap<>();
        for (PersonPatron patron : patrons.findBySheetIdOrderByIdAsc(sheetId)) {
            byPerson.computeIfAbsent(patron.getPersonId(), id -> new ArrayList<>()).add(patron.getPatronId());
        }
        return byPerson;
    }

    /** The ids of the person's patrons in the order they were named. */
    public List<Long> patronIdsOf(Long personId) {
        return patrons.findByPersonIdOrderByIdAsc(personId).stream().map(PersonPatron::getPatronId).toList();
    }

    /** Name another person on the sheet as the person's patron: not themselves, and not one already named. */
    @Transactional
    public Person addPatron(String sheetId, String user, Long id, Long patronId) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, patronId));
        if (person.getId().equals(patron.getId())) {
            throw new PatronRefusedException(person.getName() + " cannot be their own patron");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw new PatronRefusedException(patron.getName() + " is already a patron of " + person.getName());
        }
        try {
            patrons.saveAndFlush(new PersonPatron(sheetId, person.getId(), patron.getId()));
        } catch (DataIntegrityViolationException e) {
            // Another user named the same patron at the same moment: the unique index refused it.
            throw new PatronRefusedException(patron.getName() + " is already a patron of " + person.getName());
        }
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " patron=" + patron.getName());
        return person;
    }

    private static String record(String event, String user, Person person, String grade) {
        return event + " sheet=" + person.getSheetId() + " by=" + user
                + " name=" + person.getName() + " grade=" + grade;
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
