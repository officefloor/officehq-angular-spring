package net.officefloor.hq.app.person;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The people kept on a sheet. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final PersonPatronRepository patrons;
    private final PersonGradeService grades;
    private final PersonHoldRepository holds;
    private final PersonWatchRepository watches;
    private final SheetService sheets;
    private final EntityManager entityManager;
    private final Audit audit;

    public PersonService(PersonRepository people, PersonPatronRepository patrons, PersonGradeService grades,
            PersonHoldRepository holds, PersonWatchRepository watches, SheetService sheets,
            EntityManager entityManager, Audit audit) {
        this.people = people;
        this.watches = watches;
        this.patrons = patrons;
        this.grades = grades;
        this.holds = holds;
        this.entityManager = entityManager;
        this.sheets = sheets;
        this.audit = audit;
    }

    /** The sheet's people in the order they were added. */
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Add a person to the sheet; the grade they are given applies from the beginning of time. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        sheets.load(sheetId);
        String trimmed = name.trim();
        requireFreeName(sheetId, trimmed, null);
        Person added = people.saveAndFlush(new Person(sheetId, trimmed));
        grades.give(added, grade, null);
        record("PERSON_ADDED", sheetId, user, added, grade);
        return added;
    }

    /** Change a person's name, and give them the grade from a date (blank takes the sheet's today),
     *  keeping the grades already given, from an editor that loaded the given version of them. A
     *  blank from-date with the grade in force on the sheet's today gives no grade. */
    @Transactional
    public Person update(String sheetId, String user, Long id, long version, String name, String grade,
            LocalDate gradeFrom) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        if (person.getVersion() != version) {
            throw new StalePersonException(person);
        }
        String trimmed = name.trim();
        requireFreeName(sheetId, trimmed, id);
        person.setName(trimmed);
        LocalDate today = sheets.today(sheetId);
        if (gradeFrom != null || !grade.equals(grades.gradeOn(id, today))) {
            grades.give(person, grade, gradeFrom != null ? gradeFrom : today);
            // A grade given changes the person, so an editor that loaded them before is stale.
            entityManager.lock(person, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        }
        Person saved = people.saveAndFlush(person);
        record("PERSON_EDITED", sheetId, user, saved, grades.gradeOn(id, today));
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

    /** Put a hold on a person from a date (a missing date takes the sheet's today) to an optional
     *  to-date, keeping the holds they have; refused when the to-date is before the from-date. */
    @Transactional
    public Person hold(String sheetId, String user, Long id, LocalDate from, LocalDate to) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        if (to != null && to.isBefore(on)) {
            throw new HoldRefusedException("The hold's to-date " + to + " is before its from-date " + on);
        }
        holds.saveAndFlush(new PersonHold(sheetId, id, on, to));
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " from=" + on);
        return person;
    }

    /** Each person's holds on the sheet, in the order they were put on, by person id; a person with
     *  none is absent. */
    public Map<Long, List<PersonHold>> holds(String sheetId) {
        return holds.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(PersonHold::getPersonId));
    }

    /** A person's holds, in the order they were put on. */
    public List<PersonHold> holds(Long personId) {
        return holds.findByPersonIdOrderByIdAsc(personId);
    }

    /** Each person's patron ids on the sheet, in the order they were named, by person id; a person
     *  with none is absent. */
    public Map<Long, List<Long>> patronIds(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(PersonPatron::getPersonId,
                        Collectors.mapping(PersonPatron::getPatronId, Collectors.toList())));
    }

    /** A person's patron ids, in the order they were named. */
    public List<Long> patronIds(Long personId) {
        return patrons.findByPersonIdOrderByIdAsc(personId).stream().map(PersonPatron::getPatronId).toList();
    }

    /** Name another person on the sheet as a person's patron; refused for the person themselves and
     *  for someone already their patron. */
    @Transactional
    public Person addPatron(String sheetId, String user, Long id, Long patronId) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + patronId + " on sheet " + sheetId));
        if (person.getId().equals(patron.getId())) {
            throw new PatronRefusedException(person.getName() + " cannot be their own patron");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw new PatronRefusedException(patron.getName() + " is already a patron of " + person.getName());
        }
        patrons.saveAndFlush(new PersonPatron(sheetId, person.getId(), patron.getId()));
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " patron=" + patron.getName());
        return person;
    }

    /** Start or stop a user watching a person; a person already in that state is left as they are,
     *  writing nothing. */
    @Transactional
    public Person watch(String sheetId, String user, Long id, boolean on) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new NoSuchElementException("No person " + id + " on sheet " + sheetId));
        var existing = watches.findByPersonIdAndUser(id, user);
        if (on && existing.isEmpty()) {
            watches.saveAndFlush(new PersonWatch(sheetId, id, user));
            audit.recordOnCommit("PERSON_WATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
        } else if (!on && existing.isPresent()) {
            watches.delete(existing.get());
            watches.flush();
            audit.recordOnCommit("PERSON_UNWATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
        }
        return person;
    }

    /** The ids of the people a user watches on the sheet. */
    public Set<Long> watchedIds(String sheetId, String user) {
        return watches.findBySheetIdAndUser(sheetId, user).stream().map(PersonWatch::getPersonId)
                .collect(Collectors.toSet());
    }

    /** Whether a user watches a person. */
    public boolean watches(Long personId, String user) {
        return watches.findByPersonIdAndUser(personId, user).isPresent();
    }

    /** A person's watchers, in the order they started watching. */
    public List<String> watchers(Long personId) {
        return watches.findByPersonIdOrderByIdAsc(personId).stream().map(PersonWatch::getUser).toList();
    }

    private void record(String event, String sheetId, String user, Person person, String grade) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " grade=" + grade);
    }

    private void requireFreeName(String sheetId, String name, Long exceptId) {
        if (people.nameInUse(sheetId, name, exceptId)) {
            throw new NameInUseException(name);
        }
    }
}
