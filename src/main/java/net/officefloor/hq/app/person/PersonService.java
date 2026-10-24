package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
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
    private final PersonPatronRepository patrons;

    public PersonService(PersonRepository people, SheetRepository sheets, Audit audit, RollupService rollup,
            SheetService sheetService, PersonPatronRepository patrons) {
        this.people = people;
        this.patrons = patrons;
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
        audit.change("PERSON_ADDED", sheetId, user, "name=" + person.getName(), "grade=" + grade);
        rollup.recordSince(sheetId, user, before);
        return person;
    }

    /** The sheet's today; null when the sheet has never been loaded. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
    }

    /** Saves the person as edited from {@code version}, giving them {@code grade} from {@code gradeFrom}
     * (the sheet's today when left out) and keeping the grades given from other dates; refused when they
     * have been saved since. */
    @Transactional
    public Person update(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getVersion() != version) {
            throw new PersonStaleException(person);
        }
        requireFree(sheetId, name, person.getId());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate today = sheetService.today(sheetId);
        person.setName(name);
        person.setGrade(grade, gradeFrom != null ? gradeFrom : today);
        people.flush();
        audit.change("PERSON_EDITED", sheetId, user, "name=" + person.getName(), "grade=" + person.gradeOn(today));
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

    /** Puts the person on a hold from {@code from} (the sheet's today when left out) to {@code to}
     * (no end when left out); refused when it would end before it starts, or when they are already on
     * that very hold. A hold ends on its to-date: there is no release. */
    @Transactional
    public Person hold(String sheetId, String user, long personId, LocalDate from, LocalDate to) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate dated = from != null ? from : sheetService.today(sheetId);
        if (dated == null || (to != null && to.isBefore(dated))) {
            throw new PersonHoldException();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        if (!person.addHold(new PersonHold(dated, to))) {
            throw new PersonHoldException();
        }
        people.flush();
        audit.change("PERSON_HELD", sheetId, user, "name=" + person.getName(), "from=" + dated);
        rollup.recordSince(sheetId, user, before);
        return person;
    }

    /** The sheet's patrons in the order they were named. */
    @Transactional(readOnly = true)
    public List<PersonPatron> patrons(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Names another person on the sheet as the person's patron; refused for the person themselves
     * and for one they have already named. */
    @Transactional
    public PersonPatron addPatron(String sheetId, String user, long personId, long patronId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        Person patron = people.findByIdAndSheetId(patronId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getId().equals(patron.getId()) || patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw new PersonPatronException();
        }
        PersonPatron added = patrons.saveAndFlush(new PersonPatron(sheetId, person.getId(), patron.getId()));
        audit.change("PATRON_ADDED", sheetId, user, "name=" + person.getName(), "patron=" + patron.getName());
        return added;
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
