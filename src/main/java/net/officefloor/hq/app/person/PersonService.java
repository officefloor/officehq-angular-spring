package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private final PersonWatchRepository watches;

    public PersonService(PersonRepository people, SheetRepository sheets, Audit audit, RollupService rollup,
            SheetService sheetService, PersonPatronRepository patrons, PersonWatchRepository watches) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
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
    public Person add(String sheetId, String user, String name, String grade, Integer terms) {
        return add(sheetId, user, name, grade, terms, null);
    }

    /** Adds a person with their own terms (none when null) and contact (none when blank); refused when
     * another person on the sheet has the name or the contact. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        requireFree(sheetId, name, null);
        requireContactFree(sheetId, contact, null);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person created = new Person(sheetId, name, grade);
        created.setTerms(terms);
        created.setContact(contact);
        Person person = people.saveAndFlush(created);
        List<String> fields = new ArrayList<>(List.of("name=" + person.getName(), "grade=" + grade));
        if (terms != null) fields.add("terms=" + terms);
        if (person.getContact() != null) fields.add("contact=" + person.getContact());
        audit.change("PERSON_ADDED", sheetId, user, fields.toArray(String[]::new));
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
     * have been saved since. The person's own terms become {@code terms} (none when null), and their
     * contact {@code contact} (none when blank), refused when another person on the sheet has it. */
    @Transactional
    public Person update(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getVersion() != version) {
            throw new PersonStaleException(person);
        }
        requireFree(sheetId, name, person.getId());
        requireContactFree(sheetId, contact, person.getId());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate today = sheetService.today(sheetId);
        person.setName(name);
        person.setGrade(grade, gradeFrom != null ? gradeFrom : today);
        boolean termsChanged = !Objects.equals(person.getTerms(), terms);
        person.setTerms(terms);
        boolean contactChanged = !Objects.equals(person.getContact(), Person.contactOf(contact));
        person.setContact(contact);
        people.flush();
        List<String> fields = new ArrayList<>(List.of("name=" + person.getName(), "grade=" + person.gradeOn(today)));
        if (termsChanged) fields.add("terms=" + (terms == null ? "" : terms));
        if (contactChanged) fields.add("contact=" + (person.getContact() == null ? "" : person.getContact()));
        audit.change("PERSON_EDITED", sheetId, user, fields.toArray(String[]::new));
        rollup.recordSince(sheetId, user, before);
        return person;
    }

    /** Retires the person from {@code from} (the sheet's today when left out); the figures it moves are
     * recorded as {@code user}'s change. */
    @Transactional
    public Person retire(String sheetId, String user, long personId, LocalDate from) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate dated = from != null ? from : sheetService.today(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.setRetiredFrom(dated);
        people.flush();
        audit.change("PERSON_RETIRED", sheetId, user, "name=" + person.getName(), "from=" + dated);
        rollup.recordSince(sheetId, user, before);
        return person;
    }

    /** Puts the person on a hold from {@code from} (the sheet's today when left out) to {@code to}
     * (no end when left out); refused when it would end before it starts. The same hold may be put on
     * again. A hold ends on its to-date: there is no release. */
    @Transactional
    public Person hold(String sheetId, String user, long personId, LocalDate from, LocalDate to) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate dated = from != null ? from : sheetService.today(sheetId);
        if (dated == null || (to != null && to.isBefore(dated))) {
            throw new PersonHoldException();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.addHold(new PersonHold(dated, to));
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

    /** The ids of the people the user watches on the sheet, in the order they started watching. */
    @Transactional(readOnly = true)
    public List<Long> watched(String sheetId, String user) {
        return watches.findBySheetIdAndUserOrderByIdAsc(sheetId, user).stream().map(PersonWatch::getPersonId).toList();
    }

    /** Starts (on) or stops (off) the user watching the person; refused when they already do, or
     * do not. */
    @Transactional
    public Person watch(String sheetId, String user, long personId, boolean on) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        var current = watches.findByPersonIdAndUser(person.getId(), user);
        if (on == current.isPresent()) throw new PersonWatchException();
        if (on) {
            watches.saveAndFlush(new PersonWatch(sheetId, person.getId(), user));
        } else {
            watches.delete(current.get());
            watches.flush();
        }
        audit.change(on ? "PERSON_WATCHED" : "PERSON_UNWATCHED", sheetId, user, "name=" + person.getName());
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

    /** Refuses a contact another person on the sheet (other than {@code self}) already has; a blank
     * contact is none, and is never refused. */
    private void requireContactFree(String sheetId, String contact, Long self) {
        String kept = Person.contactOf(contact);
        if (kept == null) return;
        people.findBySheetIdAndContact(sheetId, kept)
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> {
                    throw new PersonContactTakenException();
                });
    }
}
