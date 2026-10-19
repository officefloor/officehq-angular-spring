package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
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
    private final SheetRepository sheets;
    private final SheetService sheetService;
    private final RollupService rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, SheetRepository sheets, SheetService sheetService,
            RollupService rollup, Audit audit) {
        this.people = people;
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
        audit.recordOnCommit(entry("PERSON_ADDED", user, person));
        rollup.recordChanges(user, sheetId, before);
        return person;
    }

    /**
     * Saves changes to a person. When {@code version} is given it is the version the editor loaded,
     * and the save is refused if the person has been saved since.
     */
    @Transactional
    public Person update(String user, String sheetId, Long id, String name, String grade, Long version) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (version != null && version != person.getVersion()) {
            throw changedMeanwhile();
        }
        String trimmed = name.trim();
        requireNameFree(sheetId, trimmed, id);
        Snapshot before = rollup.snapshot(sheetId);
        person.setName(trimmed);
        person.setGrade(grade);
        try {
            people.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw changedMeanwhile();
        }
        audit.recordOnCommit(entry("PERSON_EDITED", user, person));
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

    private static String entry(String event, String user, Person person) {
        return event + " sheet=" + person.getSheetId() + " by=" + user
                + " name=" + person.getName() + " grade=" + person.getGrade();
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
