package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.rollup.RollupService.Snapshot;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonService {

    private final PersonRepository people;
    private final PatronRepository patrons;
    private final SheetRepository sheets;
    private final SheetService sheetService;
    private final RollupService rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, PatronRepository patrons, SheetRepository sheets, SheetService sheetService,
            RollupService rollup, Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.sheets = sheets;
        this.sheetService = sheetService;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's people in the order they were added. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        requireSheet(sheetId);
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional
    public Person add(String user, String sheetId, String name, String grade) {
        requireSheet(sheetId);
        String trimmed = name.trim();
        requireNameFree(sheetId, trimmed, null);
        Snapshot before = rollup.snapshot(sheetId);
        Person person = people.saveAndFlush(new Person(sheetId, trimmed, grade));
        audit.recordOnCommit(entry("PERSON_ADDED", user, person, sheetService.today(sheetId)));
        rollup.recordChanges(user, sheetId, before);
        return person;
    }

    /**
     * Saves changes to a person. When {@code version} is given it is the version the editor loaded,
     * and the save is refused if the person has been saved since. The grade is recorded from {@code gradeFrom},
     * the sheet's today when none is given, keeping the grades the person has from other dates.
     */
    @Transactional
    public Person update(String user, String sheetId, Long id, String name, String grade, LocalDate gradeFrom,
            Long version) {
        LocalDate today = sheetService.today(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (version != null && version != person.getVersion()) {
            throw changedMeanwhile();
        }
        String trimmed = name.trim();
        requireNameFree(sheetId, trimmed, id);
        Snapshot before = rollup.snapshot(sheetId);
        person.setName(trimmed);
        person.setGrade(grade, gradeFrom != null ? gradeFrom : today);
        try {
            people.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw changedMeanwhile();
        }
        audit.recordOnCommit(entry("PERSON_EDITED", user, person, today));
        rollup.recordChanges(user, sheetId, before);
        return person;
    }

    /** Retire the person from a date, the sheet's today when none is given; claims dated after it are refused. */
    @Transactional
    public Person retire(String user, String sheetId, Long id, LocalDate from) {
        LocalDate date = from != null ? from : sheetService.today(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        person.setRetiredFrom(date);
        people.flush();
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " from=" + date);
        return person;
    }

    /** Put the person on hold, or take them off it when held; while held their OPEN claims count in no live
     * total. */
    @Transactional
    public Person toggleHold(String user, String sheetId, Long id) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        Snapshot before = rollup.snapshot(sheetId);
        person.setHeld(!person.isHeld());
        people.flush();
        audit.recordOnCommit((person.isHeld() ? "PERSON_HELD" : "PERSON_RELEASED") + " sheet=" + sheetId
                + " by=" + user + " name=" + person.getName());
        rollup.recordChanges(user, sheetId, before);
        return person;
    }

    /** Each person's patrons' ids, by person id, in the order they were named. */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> patronIds(String sheetId) {
        Map<Long, List<Long>> by = new LinkedHashMap<>();
        for (Patron patron : patrons.findBySheetIdOrderByIdAsc(sheetId)) {
            by.computeIfAbsent(patron.getPersonId(), k -> new ArrayList<>()).add(patron.getPatronId());
        }
        return by;
    }

    /** Name another person on the sheet as the person's patron; refused for the person themselves and for one
     * already named. */
    @Transactional
    public Person addPatron(String user, String sheetId, Long id, Long patronId) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patron."));
        if (patron.getId().equals(person.getId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A person cannot be their own patron.");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That patron is already named.");
        }
        patrons.saveAndFlush(new Patron(sheetId, person.getId(), patron.getId()));
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " patron=" + patron.getName());
        return person;
    }

    /** A line naming the person and the grade they have in force on the sheet's today. */
    private static String entry(String event, String user, Person person, LocalDate today) {
        return event + " sheet=" + person.getSheetId() + " by=" + user
                + " name=" + person.getName() + " grade=" + person.gradeOn(today);
    }

    private static ResponseStatusException changedMeanwhile() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Someone else has saved that person since it was opened.");
    }

    private void requireNameFree(String sheetId, String name, Long exceptId) {
        if (people.nameInUse(sheetId, name, exceptId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet.");
        }
    }

    private void requireSheet(String sheetId) {
        if (!sheets.existsById(sheetId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sheet.");
        }
    }
}
