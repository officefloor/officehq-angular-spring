package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The people kept on a sheet. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;

    public PersonService(PersonRepository people, SheetService sheets, Audit audit) {
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's people in the order they were added. */
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a person to the sheet. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        sheets.load(sheetId);
        String trimmed = name.trim();
        requireFreeName(sheetId, trimmed, null);
        Person added = people.saveAndFlush(new Person(sheetId, trimmed, grade));
        record("PERSON_ADDED", sheetId, user, added);
        return added;
    }

    /** Change a person's name and grade, from an editor that loaded the given version of them. */
    @Transactional
    public Person update(String sheetId, String user, Long id, long version, String name, String grade) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        if (person.getVersion() != version) {
            throw new StalePersonException(person);
        }
        String trimmed = name.trim();
        requireFreeName(sheetId, trimmed, id);
        person.setName(trimmed);
        person.setGrade(grade);
        Person saved = people.saveAndFlush(person);
        record("PERSON_EDITED", sheetId, user, saved);
        return saved;
    }

    /** Retire a person from a date; a missing date takes the sheet's today. */
    @Transactional
    public Person retire(String sheetId, String user, Long id, LocalDate from) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        person.setRetiredFrom(on);
        Person saved = people.saveAndFlush(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " from=" + on);
        return saved;
    }

    /** Put a person on hold, or take them off hold when they are held already. */
    @Transactional
    public Person toggleHold(String sheetId, String user, Long id) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        person.setHeld(!person.isHeld());
        Person saved = people.saveAndFlush(person);
        audit.recordOnCommit((saved.isHeld() ? "PERSON_HELD" : "PERSON_RELEASED") + " sheet=" + sheetId
                + " by=" + user + " name=" + saved.getName());
        return saved;
    }

    private void record(String event, String sheetId, String user, Person person) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " grade=" + person.getGrade());
    }

    private void requireFreeName(String sheetId, String name, Long exceptId) {
        if (people.nameInUse(sheetId, name, exceptId)) {
            throw new NameInUseException(name);
        }
    }
}
