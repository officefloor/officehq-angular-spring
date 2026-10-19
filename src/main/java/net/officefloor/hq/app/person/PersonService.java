package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetRepository sheets;
    private final Audit audit;

    public PersonService(PersonRepository people, SheetRepository sheets, Audit audit) {
        this.people = people;
        this.sheets = sheets;
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
        Person person = people.saveAndFlush(new Person(sheetId, trimmed, grade));
        audit.recordOnCommit(entry("PERSON_ADDED", user, person));
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
        person.setName(trimmed);
        person.setGrade(grade);
        try {
            people.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw changedMeanwhile();
        }
        audit.recordOnCommit(entry("PERSON_EDITED", user, person));
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
