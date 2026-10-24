package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The people kept on each sheet. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final SheetService sheetService;

    public PersonService(PersonRepository people, SheetRepository sheets, Audit audit, RollupService rollup,
            SheetService sheetService) {
        this.people = people;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
        this.sheetService = sheetService;
    }

    /** The sheet's people in the order they were added. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    @Transactional(readOnly = true)
    public Person get(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        requireFree(sheetId, name, null);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.saveAndFlush(new Person(sheetId, name, grade));
        audit.change("PERSON_ADDED", sheetId, user, "name=" + person.getName(), "grade=" + person.getGrade());
        rollup.recordSince(sheetId, user, before);
        return person;
    }

    /** Saves the person as edited from {@code version}; refused when they have been saved since. */
    @Transactional
    public Person update(String sheetId, String user, long personId, long version, String name, String grade) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getVersion() != version) {
            throw new PersonStaleException(person);
        }
        requireFree(sheetId, name, person.getId());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.setName(name);
        person.setGrade(grade);
        people.flush();
        audit.change("PERSON_EDITED", sheetId, user, "name=" + person.getName(), "grade=" + person.getGrade());
        rollup.recordSince(sheetId, user, before);
        return person;
    }

    /** Retires the person from {@code from} (the sheet's today when left out). */
    @Transactional
    public Person retire(String sheetId, String user, long personId, LocalDate from) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate dated = from != null ? from : sheetService.today(sheetId);
        person.setRetiredFrom(dated);
        people.flush();
        audit.change("PERSON_RETIRED", sheetId, user, "name=" + person.getName(), "from=" + dated);
        return person;
    }

    /** Puts the person on hold ({@code held}) or takes them off it; refused when they already are
     * as asked. */
    @Transactional
    public Person hold(String sheetId, String user, long personId, boolean held) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.isHeld() == held) {
            throw new PersonHoldException();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.setHeld(held);
        people.flush();
        audit.change(held ? "PERSON_HELD" : "PERSON_RELEASED", sheetId, user, "name=" + person.getName());
        rollup.recordSince(sheetId, user, before);
        return person;
    }

    /** Refuses a name another person on the sheet (other than {@code self}) already has. */
    private void requireFree(String sheetId, String name, Long self) {
        people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> {
                    throw new PersonNameTakenException();
                });
    }
}
