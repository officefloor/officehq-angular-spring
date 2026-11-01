package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The people on a sheet: listed in the order they were added, added and edited one at a time. A
 * name already used by another person on the sheet (ignoring case and surrounding spaces) is
 * refused, and so is an edit made from a version of the person someone else has saved since. A
 * person can be retired from a date (blank: the sheet's today), and put on hold from a from-date (blank: the sheet's today) to an optional to-date, any number of
 * times. A
 * person can name other people on the sheet as their patrons, one at a time; not themselves, and
 * not the same person twice. A person's grade is dated: the first one given is in force from the
 * beginning of time, and each edit records the grade from its from-date (blank: the sheet's today),
 * unless it leaves the grade as in force on the sheet's today with the from-date blank. A person may
 * have their own terms in days (null: none), given on the add and on each edit.
 */
@Service
public class PersonService {

    private final PersonRepository people;
    private final PersonPatronRepository patrons;
    private final PersonWatchRepository watches;
    private final SheetRepository sheets;
    private final ClaimRepository claims;
    private final RollupService rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, PersonPatronRepository patrons,
            PersonWatchRepository watches, SheetRepository sheets, ClaimRepository claims, RollupService rollup,
            Audit audit) {
        this.claims = claims;
        this.watches = watches;
        this.people = people;
        this.patrons = patrons;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The ids of the sheet's people who are active on its today ({@link Person#activeOn}). */
    @Transactional(readOnly = true)
    public Set<Long> activeIds(String sheetId) {
        LocalDate today = today(sheetId);
        List<Claim> all = claims.findBySheetIdOrderByIdDesc(sheetId);
        Set<Long> ids = new HashSet<>();
        for (Person person : people.findBySheetIdOrderByIdAsc(sheetId)) {
            if (person.activeOn(all, today)) ids.add(person.getId());
        }
        return ids;
    }

    /** Whether the person is active on their sheet's today. */
    @Transactional(readOnly = true)
    public boolean isActive(Person person) {
        return person.activeOn(claims.findBySheetIdAndPersonIdOrderByIdDesc(person.getSheetId(), person.getId()),
                today(person.getSheetId()));
    }

    /** The sheet's today, or null when there is no such sheet. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        return add(sheetId, user, name, grade, null);
    }

    /** Add a person with their own terms in days (null: none). */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms) {
        if (!sheets.existsById(sheetId)) throw new SheetNotFoundException();
        requireUnused(sheetId, name, null);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person draft = new Person(sheetId, name, grade);
        draft.setTerms(terms);
        Person person = saveUnique(draft);
        record("PERSON_ADDED", sheetId, user, person, person.getGrade(),
                terms != null ? " terms=" + terms : "");
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    @Transactional
    public Person edit(String sheetId, String user, Long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms) {
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getVersion() != version) throw new PersonVersionConflictException();
        requireUnused(sheetId, name, id);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate today = today(sheetId);
        person.setName(name);
        boolean termsChanged = !Objects.equals(terms, person.getTerms());
        person.setTerms(terms);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.setGradeFrom(gradeFrom != null ? gradeFrom : today, grade);
        }
        Person saved = saveUnique(person);
        record("PERSON_EDITED", sheetId, user, saved, saved.gradeOn(today),
                termsChanged ? " terms=" + (terms != null ? terms : "") : "");
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    @Transactional
    public Person retire(String sheetId, String user, Long id, LocalDate from) {
        // Lock the sheet so no claim is added or edited for them meanwhile against the old date.
        LocalDate today = sheets.findForUpdate(sheetId).orElseThrow(SheetNotFoundException::new).getToday();
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        // Once the sheet's today reaches the date, their open claims leave the totals.
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate on = from != null ? from : today;
        person.setRetiredFrom(on);
        Person saved = saveUnique(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user.strip()
                + " name=" + saved.getName() + " from=" + on);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Put the person on hold from the date (blank: the sheet's today) to the optional to-date; their
     * open (OPEN or PENDING) claims dated within it, unless bound by a statement, leave the totals.
     */
    @Transactional
    public Person hold(String sheetId, String user, Long id, LocalDate from, LocalDate to) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate on = from != null ? from : today(sheetId);
        if (on == null) throw new SheetNotFoundException();
        if (to != null && to.isBefore(on)) throw new HoldDatesException();
        person.addHold(new PersonHold(on, to));
        Person saved = saveUnique(person);
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user.strip()
                + " name=" + saved.getName() + " from=" + on);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** Each person's patrons' ids on the sheet, by person id, in the order they were named. */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> patronIds(String sheetId) {
        Map<Long, List<Long>> by = new HashMap<>();
        for (PersonPatron p : patrons.findBySheetIdOrderByIdAsc(sheetId)) {
            by.computeIfAbsent(p.getPersonId(), k -> new ArrayList<>()).add(p.getPatronId());
        }
        return by;
    }

    /** The person's patrons' ids, in the order they were named. */
    @Transactional(readOnly = true)
    public List<Long> patronIdsOf(Long personId) {
        return patrons.findByPersonIdOrderByIdAsc(personId).stream().map(PersonPatron::getPatronId).toList();
    }

    /** Name another person on the sheet as the person's patron; not themselves, nor one already named. */
    @Transactional
    public Person addPatron(String sheetId, String user, Long id, Long patronId) {
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        Person patron = people.findByIdAndSheetId(patronId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getId().equals(patron.getId())) throw new PatronRefusedException();
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) throw new PatronRefusedException();
        try {
            patrons.saveAndFlush(new PersonPatron(sheetId, person.getId(), patron.getId()));
        } catch (DataIntegrityViolationException e) {
            // named by another user meanwhile
            throw new PatronRefusedException();
        }
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user.strip()
                + " name=" + person.getName() + " patron=" + patron.getName());
        return person;
    }

    /** The ids of the people on the sheet the user watches. */
    @Transactional(readOnly = true)
    public Set<Long> watchedIds(String sheetId, String user) {
        return watches.findBySheetIdAndUserName(sheetId, user.strip()).stream()
                .map(PersonWatch::getPersonId).collect(Collectors.toSet());
    }

    /** Whether the user watches the person. */
    @Transactional(readOnly = true)
    public boolean watches(Long personId, String user) {
        return watches.findByPersonIdAndUserName(personId, user.strip()).isPresent();
    }

    /** The users watching the person, in the order they started watching. */
    @Transactional(readOnly = true)
    public List<String> watchersOf(Long personId) {
        return watches.findByPersonIdOrderByIdAsc(personId).stream().map(PersonWatch::getUserName).toList();
    }

    /** Start (true) or stop (false) watching the person as the user; already so, nothing changes. */
    @Transactional
    public Person watch(String sheetId, String user, Long id, boolean on) {
        Person person = people.findByIdAndSheetId(id, sheetId).orElseThrow(PersonNotFoundException::new);
        String name = user.strip();
        var existing = watches.findByPersonIdAndUserName(person.getId(), name);
        if (on == existing.isPresent()) return person;
        if (on) {
            try {
                watches.saveAndFlush(new PersonWatch(sheetId, person.getId(), name));
            } catch (DataIntegrityViolationException e) {
                // started meanwhile in another window
                return person;
            }
        } else {
            watches.delete(existing.get());
            watches.flush();
        }
        audit.recordOnCommit((on ? "PERSON_WATCHED" : "PERSON_UNWATCHED") + " sheet=" + sheetId + " by=" + name
                + " name=" + person.getName());
        return person;
    }

    private void record(String event, String sheetId, String user, Person person, String grade, String tail) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user.strip()
                + " name=" + person.getName() + " grade=" + grade + tail);
    }

    private void requireUnused(String sheetId, String name, Long self) {
        people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> { throw new DuplicatePersonNameException(); });
    }

    /** Writes the person now, so a name taken meanwhile by another user is refused by the store. */
    private Person saveUnique(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            throw new DuplicatePersonNameException();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new PersonVersionConflictException();
        }
    }
}
