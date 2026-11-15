package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PersonService {

    private final PersonRepository people;
    private final PersonStatementRepository statements;
    private final PersonPatronRepository patrons;
    private final PersonWatchRepository watches;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, PersonStatementRepository statements,
            PersonPatronRepository patrons, PersonWatchRepository watches, SheetService sheets, RollupService rollup,
            Audit audit) {
        this.people = people;
        this.statements = statements;
        this.patrons = patrons;
        this.watches = watches;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** The sheet's today: the date the grade a person shows is in force on. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
    }

    @Transactional(readOnly = true)
    public Person get(String sheetId, long id) {
        return people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
    }

    /** Each person's statement summary, by person id; a person with none is missing. */
    @Transactional(readOnly = true)
    public Map<Long, StatementSummary> statementSummaries(String sheetId) {
        return statements.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(PersonStatement::getPersonId,
                        Collectors.collectingAndThen(Collectors.toList(), StatementSummary::of)));
    }

    @Transactional(readOnly = true)
    public StatementSummary statementSummary(long personId) {
        return StatementSummary.of(statements.findByPersonIdOrderByIdAsc(personId));
    }

    /** Each person's patron ids in the order they were named, by person id; a person with none is missing. */
    @Transactional(readOnly = true)
    public Map<Long, List<Long>> patronIds(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId).stream()
                .collect(Collectors.groupingBy(PersonPatron::getPersonId,
                        Collectors.mapping(PersonPatron::getPatronId, Collectors.toList())));
    }

    @Transactional(readOnly = true)
    public List<Long> patronIds(long personId) {
        return patrons.findByPersonIdOrderByIdAsc(personId).stream().map(PersonPatron::getPatronId).toList();
    }

    /** The ids of the people on the sheet the user watches. */
    @Transactional(readOnly = true)
    public Set<Long> watchedIds(String sheetId, String user) {
        return watches.findBySheetIdAndUserName(sheetId, user).stream().map(PersonWatch::getPersonId)
                .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public boolean isWatching(long personId, String user) {
        return watches.existsByPersonIdAndUserName(personId, user);
    }

    /** Start (on) or stop watching the person as the user; already so, nothing changes; refused
     *  (403) on an archived sheet. */
    @Transactional
    public Person watch(String sheetId, String user, long id, boolean on) {
        if (sheets.archived(sheetId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The sheet is archived: nothing on it may change");
        }
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (on) {
            if (watches.existsByPersonIdAndUserName(person.getId(), user)) {
                return person;
            }
            watches.saveAndFlush(new PersonWatch(sheetId, person.getId(), user));
            audit.recordOnCommit("PERSON_WATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
        } else if (watches.deleteByPersonIdAndUserName(person.getId(), user) > 0) {
            audit.recordOnCommit("PERSON_UNWATCHED sheet=" + sheetId + " by=" + user + " name=" + person.getName());
        }
        return person;
    }

    /** Name another person on the sheet as the person's patron; refused for the person themselves
     *  and for a patron they already have. */
    @Transactional
    public Person addPatron(String sheetId, String user, long id, long patronId) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patron"));
        if (person.getId().equals(patron.getId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "A person cannot be their own patron");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw patronAlready();
        }
        try {
            patrons.saveAndFlush(new PersonPatron(sheetId, person.getId(), patron.getId()));
        } catch (DataIntegrityViolationException e) {
            throw patronAlready(); // named by another user at the same moment
        }
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " patron=" + patron.getName());
        return person;
    }

    /** Add a person to the sheet (the sheet is created first if it was never loaded). */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms) {
        sheets.load(sheetId, user);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String trimmed = name.trim();
        requireUnusedName(sheetId, null, trimmed);
        Person person;
        try {
            Person fresh = new Person(sheetId, trimmed, grade);
            fresh.setTerms(terms);
            person = people.saveAndFlush(fresh);
        } catch (DataIntegrityViolationException e) {
            throw nameInUse(); // added by another user at the same moment
        }
        record("PERSON_ADDED", sheetId, user, person, sheets.today(sheetId), terms == null ? "" : termsField(terms));
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** Save a person; refused when {@code version} (the one the editor loaded) is no longer the stored one.
     *  The grade is recorded from {@code gradeFrom} (null: the sheet's today), keeping those already
     *  recorded; with no from-date, a grade the same as the one in force on the sheet's today records nothing.
     *  The person's own terms become {@code terms} (null: none). */
    @Transactional
    public Person update(String sheetId, String user, long id, Long version, String name, String grade,
            LocalDate gradeFrom, Integer terms) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        if (version == null || version != person.getVersion()) {
            throw savedByAnother();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String trimmed = name.trim();
        requireUnusedName(sheetId, id, trimmed);
        person.setName(trimmed);
        boolean termsChanged = !Objects.equals(terms, person.getTerms());
        person.setTerms(terms);
        LocalDate today = sheets.today(sheetId);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.recordGrade(grade, gradeFrom != null ? gradeFrom : today);
        }
        try {
            people.flush();
        } catch (DataIntegrityViolationException e) {
            throw nameInUse(); // taken by another user at the same moment
        } catch (ObjectOptimisticLockingFailureException e) {
            throw savedByAnother(); // saved by another user at the same moment
        }
        record("PERSON_EDITED", sheetId, user, person, today, termsChanged ? termsField(terms) : "");
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** Give each of the people of {@code ids}, in list order, {@code grade} from {@code gradeFrom}
     *  (null: the sheet's today) as saving them in the person form would, each written with its
     *  PERSON_EDITED and rollup lines; all or none. Refused (404) when any is not on the sheet. */
    @Transactional
    public int grade(String sheetId, String user, List<Long> ids, String grade, LocalDate gradeFrom) {
        Set<Long> wanted = Set.copyOf(ids);
        List<Person> chosen = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> wanted.contains(p.getId())).toList();
        if (chosen.size() != wanted.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person");
        }
        LocalDate today = sheets.today(sheetId);
        for (Person person : chosen) {
            RollupService.Snapshot before = rollup.snapshot(sheetId);
            if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
                person.recordGrade(grade, gradeFrom != null ? gradeFrom : today);
            }
            try {
                people.flush();
            } catch (ObjectOptimisticLockingFailureException e) {
                throw savedByAnother(); // saved by another user at the same moment
            }
            record("PERSON_EDITED", sheetId, user, person, today, "");
            rollup.recordChanges(sheetId, user, before);
        }
        return chosen.size();
    }

    /** Retire a person from a date (a missing one takes the sheet's today); they count as retired
     *  at once. Retiring them again moves the date. From the day the date is reached their open
     *  claims, unless bound by a statement, leave the live totals. */
    @Transactional
    public Person retire(String sheetId, String user, long id, LocalDate from) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.setRetiredFrom(on);
        people.flush();
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " from=" + on);
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** Add a hold to a person from a date (a missing one takes the sheet's today) to an optional
     *  one; their OPEN claims dated within it then count in no live total, unless bound by a
     *  statement. A to-date before the from-date is refused (422). */
    @Transactional
    public Person hold(String sheetId, String user, long id, LocalDate from, LocalDate to) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person"));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        if (to != null && to.isBefore(on)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "The to-date is before the from-date");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.addHold(on, to);
        people.flush();
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " from=" + on);
        rollup.recordChanges(sheetId, user, before);
        return person;
    }

    /** Written with the grade in force on {@code today}, the sheet's today, then {@code extra}. */
    private void record(String event, String sheetId, String user, Person person, LocalDate today, String extra) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " grade=" + person.gradeOn(today) + extra);
    }

    /** The terms field of a person's record: the days, empty when none. */
    private static String termsField(Integer terms) {
        return " terms=" + (terms == null ? "" : terms);
    }

    /** Refuse a name another person on the sheet already has (ignoring case). */
    private void requireUnusedName(String sheetId, Long exceptId, String name) {
        boolean taken = people.findBySheetIdAndNameIgnoreCase(sheetId, name).stream()
                .anyMatch(p -> !p.getId().equals(exceptId));
        if (taken) {
            throw nameInUse();
        }
    }

    private static ResponseStatusException savedByAnother() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Saved by someone else since it was loaded");
    }

    private static ResponseStatusException patronAlready() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Already a patron");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Name already in use");
    }
}
