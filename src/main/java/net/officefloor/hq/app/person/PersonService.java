package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonService {

    private final PersonRepository people;
    private final PersonPatronRepository patrons;
    private final PersonWatchRepository watches;
    private final SheetService sheets;
    private final Rollup rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, PersonPatronRepository patrons, PersonWatchRepository watches,
            SheetService sheets, Rollup rollup, Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    /** The sheet's people in the order they were added. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The ids of each person's patrons on the sheet, by person, in the order they were named; a person
     *  with no patrons is left out. */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> patronIds(String sheetId) {
        Map<Long, List<Long>> by = new LinkedHashMap<>();
        for (PersonPatron patron : patrons.findBySheetIdOrderByIdAsc(sheetId)) {
            by.computeIfAbsent(patron.getPersonId(), p -> new ArrayList<>()).add(patron.getPatronId());
        }
        return by;
    }

    /** The ids of the person's patrons, in the order they were named. */
    @Transactional(readOnly = true)
    public List<Long> patronIdsOf(long id) {
        return patrons.findByPersonIdOrderByIdAsc(id).stream().map(PersonPatron::getPatronId).toList();
    }

    /** Name another person on the sheet as one of the person's patrons; refused for the person
     *  themselves and for someone already their patron. */
    @Transactional
    public Person addPatron(String sheetId, String user, long id, long patronId) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patron."));
        if (person.getId().equals(patron.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be their own patron.");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw alreadyPatron();
        }
        try {
            patrons.saveAndFlush(new PersonPatron(sheetId, person.getId(), patron.getId()));
        } catch (DataIntegrityViolationException raced) {
            // Another user named the same patron at the same moment.
            throw alreadyPatron();
        }
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " patron=" + patron.getName());
        return person;
    }

    /** The ids of the people on the sheet the user watches. */
    @Transactional(readOnly = true)
    public Set<Long> watchedIds(String sheetId, String user) {
        return watches.findBySheetIdAndUserName(sheetId, user).stream().map(PersonWatch::getPersonId)
                .collect(Collectors.toSet());
    }

    /** Whether the user watches the person. */
    @Transactional(readOnly = true)
    public boolean isWatched(long id, String user) {
        return watches.existsByPersonIdAndUserName(id, user);
    }

    /** Start (true) or stop (false) the user watching the person; when the user already does as asked,
     *  nothing changes. A user who starts watching again is told after those watching meanwhile. */
    @Transactional
    public Person watch(String sheetId, String user, long id, boolean on) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        Optional<PersonWatch> watching = watches.findByPersonIdAndUserName(person.getId(), user);
        if (on == watching.isPresent()) return person;
        if (on) {
            try {
                watches.saveAndFlush(new PersonWatch(sheetId, person.getId(), user));
            } catch (DataIntegrityViolationException raced) {
                // The same user started watching from another screen at the same moment.
                throw new ResponseStatusException(HttpStatus.CONFLICT, "You already watch this person.");
            }
        } else {
            watches.delete(watching.get());
            watches.flush();
        }
        audit.recordOnCommit((on ? "PERSON_WATCHED" : "PERSON_UNWATCHED") + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName());
        return person;
    }

    private static ResponseStatusException alreadyPatron() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That person is already a patron.");
    }

    /** Add a person to the sheet. */
    @Transactional
    public Person add(String sheetId, String user, String name, Grade grade, Integer terms) {
        sheets.load(sheetId);
        if (people.existsBySheetIdAndNameKey(sheetId, Person.keyOf(name))) {
            throw nameInUse();
        }
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        Person fresh = new Person(sheetId, name, grade);
        fresh.setTerms(terms);
        Person person = save(fresh);
        recordChange("PERSON_ADDED", user, person, terms == null ? "" : " terms=" + terms);
        rollup.recordChanges(user, before);
        return person;
    }

    /** Change a person's name and give them a grade from a from-date (none: the sheet's today), keeping
     *  the grades they were given from other dates; refused when someone has saved the person since
     *  the editor loaded this version of them. */
    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, Grade grade,
            LocalDate gradeFrom, Integer terms) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (person.getVersion() != version) {
            throw changedMeanwhile();
        }
        if (people.existsBySheetIdAndNameKeyAndIdNot(sheetId, Person.keyOf(name), id)) {
            throw nameInUse();
        }
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        boolean termsChanged = !Objects.equals(person.getTerms(), terms);
        person.setName(name);
        person.setGrade(grade, gradeFrom != null ? gradeFrom : sheets.today(sheetId));
        person.setTerms(terms);
        Person saved = save(person);
        recordChange("PERSON_EDITED", user, saved, termsChanged ? " terms=" + (terms == null ? "" : terms) : "");
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Retire a person from a date, the sheet's today when none is given. Their claims keep counting;
     *  claims dated after the date can no longer be added to them. */
    @Transactional
    public Person retire(String sheetId, String user, long id, LocalDate from) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        LocalDate dated = from != null ? from : sheets.today(sheetId);
        person.setRetiredFrom(dated);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " from=" + dated);
        return saved;
    }

    /** Put a hold on a person from a date (none: the sheet's today) to a date (none: no end); refused
     *  when it ends before it starts. Their open claims dated within it count in no live total, unless
     *  a statement binds them; their settled claims still count. */
    @Transactional
    public Person hold(String sheetId, String user, long id, LocalDate from, LocalDate to) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        LocalDate dated = from != null ? from : sheets.today(sheetId);
        if (to != null && to.isBefore(dated)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A hold cannot end before it starts.");
        }
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        person.hold(dated, to);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " from=" + dated);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** The person's audit line, with the grade in force on the sheet's today, then the fields given. */
    private void recordChange(String event, String user, Person person, String fields) {
        audit.recordOnCommit(event + " sheet=" + person.getSheetId() + " by=" + user
                + " name=" + person.getName() + " grade=" + person.gradeOn(sheets.today(person.getSheetId()))
                + fields);
    }

    private Person save(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (OptimisticLockingFailureException raced) {
            // Another user saved the same person at the same moment.
            throw changedMeanwhile();
        } catch (DataIntegrityViolationException raced) {
            // Another user took the same name at the same moment.
            throw nameInUse();
        }
    }

    private static ResponseStatusException changedMeanwhile() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Someone else has saved this person since it was opened.");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on the sheet.");
    }
}
