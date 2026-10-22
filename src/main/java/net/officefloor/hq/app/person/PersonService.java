package net.officefloor.hq.app.person;

import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The people kept on a sheet: listed, added and edited. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;
    private final Rollups rollups;
    private final Audit audit;

    public PersonService(PersonRepository people, SheetService sheets, Rollups rollups, Audit audit) {
        this.people = people;
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** The sheet's people in the order they were added. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a person to the sheet; refused when the name is already in use on it. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        sheets.load(sheetId);
        requireNameFree(sheetId, name, null);
        Rollups.Rollup before = rollups.take(sheetId);
        Person person = people.saveAndFlush(new Person(sheetId, name, grade));
        audited("PERSON_ADDED", sheetId, user, person);
        rollups.audit(sheetId, user, before);
        return person;
    }

    /**
     * Change a person's name and grade, from the version of the person the editor loaded; refused when
     * someone has saved the person since, or when the name is in use by another person.
     */
    @Transactional
    public Person edit(String sheetId, String user, long personId, long version, String name, String grade) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (person.getVersion() != version) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "Someone else has saved this person since it was opened.");
        }
        requireNameFree(sheetId, name, person.getId());
        Rollups.Rollup before = rollups.take(sheetId);
        person.setName(name);
        person.setGrade(grade);
        Person saved = people.saveAndFlush(person);
        audited("PERSON_EDITED", sheetId, user, saved);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /** Record the change to the person once it is committed. */
    private void audited(String event, String sheetId, String user, Person person) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " grade=" + person.getGrade());
    }

    /** Refuse a name already held on the sheet by anyone other than {@code self}. */
    private void requireNameFree(String sheetId, String name, Long self) {
        people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                .filter(holder -> !holder.getId().equals(self))
                .ifPresent(holder -> {
                    throw nameInUse();
                });
    }

    static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet.");
    }
}
