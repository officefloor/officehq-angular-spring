package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The people kept on a sheet. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final PatronRepository patrons;
    private final SheetService sheets;
    private final RollupService rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, PatronRepository patrons, SheetService sheets, RollupService rollup, Audit audit) {
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

    /** The sheet's today, which the grade a person is listed with is in force on. */
    @Transactional
    public LocalDate today(String sheetId) {
        return sheets.load(sheetId).getToday();
    }

    /** Adds the person with no terms of their own. */
    @Transactional
    public Person add(String by, String sheetId, String name, String grade) {
        return add(by, sheetId, name, grade, null);
    }

    /**
     * Adds the person with their own terms (null: none); the grade they are given first applies from the beginning
     * of time.
     */
    @Transactional
    public Person add(String by, String sheetId, String name, String grade, Integer terms) {
        return add(by, sheetId, name, grade, terms, null);
    }

    /**
     * Adds the person with their own terms (null: none) and contact (blank or null: none); the grade they are given
     * first applies from the beginning of time. Refused when another person on the sheet has the contact.
     */
    @Transactional
    public Person add(String by, String sheetId, String name, String grade, Integer terms, String contact) {
        LocalDate today = sheets.load(sheetId).getToday();
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        String trimmed = name.strip();
        if (people.nameOrAliasInUse(sheetId, trimmed)) {
            throw nameInUse();
        }
        String key = contactKey(contact);
        if (key != null && people.existsBySheetIdAndContact(sheetId, key)) {
            throw contactInUse();
        }
        Person person = new Person(sheetId, trimmed, grade, today);
        person.setTerms(terms);
        person.setContact(key);
        Person added = save(person);
        audit.recordOnCommit(record("PERSON_ADDED", by, added, today(sheetId))
                + (terms != null ? " terms=" + terms : "")
                + (key != null ? " contact=" + key : ""));
        rollup.recordChanges(by, sheetId, before);
        return added;
    }

    /**
     * Saves an edit made from the given version of the person; refused when it has been saved since. The grade is
     * recorded from the from-date (missing: the sheet's today), keeping the ones already recorded, except that an
     * edit leaving the grade in force today as it is, with no from-date, records nothing. The person's own terms are
     * set to the given ones (null: none), and their contact to the given one (blank or null: none), refused when
     * another person on the sheet has it.
     */
    @Transactional
    public Person update(String by, String sheetId, Long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        LocalDate today = today(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (person.getVersion() != version) {
            throw stale();
        }
        String trimmed = name.strip();
        if (people.existsBySheetIdAndNameIgnoreCaseAndIdNot(sheetId, trimmed, id)
                || people.findBySheetIdAndAlias(sheetId, trimmed).isPresent()) {
            throw nameInUse();
        }
        String key = contactKey(contact);
        if (key != null && people.existsBySheetIdAndContactAndIdNot(sheetId, key, id)) {
            throw contactInUse();
        }
        person.setName(trimmed);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.recordGrade(grade, gradeFrom != null ? gradeFrom : today, today);
        }
        boolean termsChanged = !Objects.equals(terms, person.getTerms());
        person.setTerms(terms);
        boolean contactChanged = !Objects.equals(key, person.getContact());
        person.setContact(key);
        Person saved = save(person);
        audit.recordOnCommit(record("PERSON_EDITED", by, saved, today)
                + (termsChanged ? " terms=" + (terms != null ? terms : "") : "")
                + (contactChanged ? " contact=" + (key != null ? key : "") : ""));
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Gives each of the people a grade from the from-date (missing: the sheet's today), in list order, exactly as
     * saving each one's edit with that grade would, all or none: one that is not a person of the sheet refuses them
     * all. The people as saved, in list order.
     */
    @Transactional
    public List<Person> bulkGrade(String by, String sheetId, Set<Long> ids, String grade, LocalDate from) {
        List<Person> chosen = people.findBySheetIdOrderByIdAsc(sheetId).stream()
                .filter(p -> ids.contains(p.getId())).toList();
        if (chosen.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person.");
        }
        List<Person> saved = new ArrayList<>();
        for (Person p : chosen) {
            saved.add(update(by, sheetId, p.getId(), p.getVersion(), p.getName(), grade, from, p.getTerms(),
                    p.getContact()));
        }
        return saved;
    }

    /** Retires the person from the date; a missing date takes the sheet's today. Their claims keep counting. */
    @Transactional
    public Person retire(String by, String sheetId, Long id, LocalDate from) {
        LocalDate today = sheets.load(sheetId).getToday();
        LocalDate on = from != null ? from : today;
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        person.setRetiredFrom(on, today);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + by + " name=" + saved.getName()
                + " from=" + on);
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Puts a hold on the person from the date (missing: the sheet's today) to the to-date (missing: with no end); the
     * holds already put are kept, each ending on its to-date. Their open claims dated within a hold, not bound by a
     * statement, count in no live total. Refused when the to-date is before the from-date.
     */
    @Transactional
    public Person hold(String by, String sheetId, Long id, LocalDate from, LocalDate to) {
        LocalDate today = sheets.load(sheetId).getToday();
        LocalDate on = from != null ? from : today;
        if (to != null && to.isBefore(on)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A hold cannot end before it starts.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        person.addHold(on, to, today);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + by + " name=" + saved.getName()
                + " from=" + on);
        rollup.recordChanges(by, sheetId, before);
        return saved;
    }

    /**
     * Gives the person the alias, without surrounding spaces; refused when blank, or when it equals, ignoring case,
     * any name or alias on the sheet.
     */
    @Transactional
    public Person addAlias(String by, String sheetId, Long id, String alias) {
        String trimmed = alias == null ? "" : alias.strip();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an alias.");
        }
        Person person = people.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (people.nameOrAliasInUse(sheetId, trimmed)) {
            throw nameInUse();
        }
        person.addAlias(trimmed);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_ALIAS_ADDED sheet=" + sheetId + " by=" + by + " name=" + saved.getName()
                + " alias=" + trimmed);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Patron> patrons(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Names another person on the sheet as the person's patron; never themselves, nor anyone twice. */
    @Transactional
    public Patron addPatron(String by, String sheetId, Long id, Long patronId) {
        Person person = people.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patron."));
        if (person.getId().equals(patron.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A person cannot be their own patron.");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw patronNamed();
        }
        Patron added;
        try {
            added = patrons.saveAndFlush(new Patron(sheetId, person.getId(), patron.getId()));
        } catch (DataIntegrityViolationException e) {
            throw patronNamed();
        }
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + by + " name=" + person.getName()
                + " patron=" + patron.getName());
        return added;
    }

    /** A contact as stored: without surrounding spaces and lower-cased; null when blank. */
    private static String contactKey(String contact) {
        if (contact == null || contact.isBlank()) {
            return null;
        }
        return contact.strip().toLowerCase(Locale.ROOT);
    }

    /** Saves the person; the store's unique indexes refuse a name or contact another request took meanwhile. */
    private Person save(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            throw nameOrContactInUse();
        } catch (ObjectOptimisticLockingFailureException e) {
            // another request saved the person between this one reading and writing it
            throw stale();
        }
    }

    /** The audit line about a person, with the grade in force on the sheet's today. */
    private static String record(String event, String by, Person p, LocalDate today) {
        return event + " sheet=" + p.getSheetId() + " by=" + by + " name=" + p.getName() + " grade="
                + p.gradeOn(today);
    }

    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Someone else has saved this person since it was opened.");
    }

    private static ResponseStatusException patronNamed() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That person is already one of their patrons.");
    }

    private static ResponseStatusException contactInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That contact is already in use on this sheet.");
    }

    private static ResponseStatusException nameOrContactInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "That name or contact is already in use on this sheet.");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet.");
    }
}
