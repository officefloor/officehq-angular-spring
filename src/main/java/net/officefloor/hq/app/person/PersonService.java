package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetRepository;
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
    private final SheetRepository sheetRepository;
    private final RollupService rollup;
    private final Audit audit;

    public PersonService(PersonRepository people, PatronRepository patrons, SheetService sheets,
            SheetRepository sheetRepository, RollupService rollup, Audit audit) {
        this.people = people;
        this.patrons = patrons;
        this.sheets = sheets;
        this.sheetRepository = sheetRepository;
        this.rollup = rollup;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
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
        if (nameOrAliasInUse(sheetId, trimmed)) {
            throw nameInUse();
        }
        String key = contactKey(contact);
        if (key != null && people.existsBySheetIdAndContact(sheetId, key)) {
            throw contactInUse();
        }
        Person person = new Person(sheetId, trimmed, grade, today);
        person.setTerms(terms);
        person.setContact(key);
        // the new person goes to the end of the list
        sheets.lock(sheetId);
        person.setPosition(people.nextPosition(sheetId));
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
        return update(by, sheetId, id, version, name, grade, gradeFrom, terms, contact, true);
    }

    /**
     * Saves the edit as {@link #update(String, String, Long, long, String, String, LocalDate, Integer, String)} does;
     * when checking the tree, refused too when the name, or any alias the person keeps, is a name or alias on another
     * sheet of the sheet's tree.
     */
    private Person update(String by, String sheetId, Long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact, boolean checkTree) {
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
        if (checkTree) {
            Set<String> tree = tree(sheetId);
            if (inUseOn(tree, trimmed) || person.getAliases().stream().anyMatch(a -> inUseOn(tree, a))) {
                throw nameInUse();
            }
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
        List<Person> chosen = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> ids.contains(p.getId())).toList();
        if (chosen.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person.");
        }
        List<Person> saved = new ArrayList<>();
        for (Person p : chosen) {
            saved.add(update(by, sheetId, p.getId(), p.getVersion(), p.getName(), grade, from, p.getTerms(),
                    p.getContact(), false));
        }
        return saved;
    }

    /**
     * Swaps the person with the one above them in the list (up) or below them (down); refused for the top person
     * moving up and the bottom one moving down. Neither person's version changes: their place is not an edit of them.
     */
    @Transactional
    public void move(String by, String sheetId, Long id, boolean up) {
        sheets.lock(sheetId);
        List<Person> listed = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        int at = -1;
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).getId().equals(id)) {
                at = i;
            }
        }
        if (at < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person.");
        }
        int to = up ? at - 1 : at + 1;
        if (to < 0 || to >= listed.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    up ? "The top person cannot move up." : "The bottom person cannot move down.");
        }
        Person person = listed.get(at);
        Person other = listed.get(to);
        // the positions are renumbered from 1 in list order, so swapping two always changes their order
        for (int i = 0; i < listed.size(); i++) {
            Person p = i == at ? other : i == to ? person : listed.get(i);
            if (p.getPosition() != i + 1) {
                people.placeAt(p.getId(), i + 1);
            }
        }
        audit.recordOnCommit("PERSON_MOVED sheet=" + sheetId + " by=" + by + " name=" + person.getName()
                + " position=" + (to + 1));
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
     * Retires each of the people from the date (missing: the sheet's today), in list order, exactly as each one's
     * retire would, all or none: one that is not a person of the sheet refuses them all. The people as saved, in list
     * order.
     */
    @Transactional
    public List<Person> bulkRetire(String by, String sheetId, Set<Long> ids, LocalDate from) {
        List<Person> chosen = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> ids.contains(p.getId())).toList();
        if (chosen.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person.");
        }
        List<Person> saved = new ArrayList<>();
        for (Person p : chosen) {
            saved.add(retire(by, sheetId, p.getId(), from));
        }
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
     * any name or alias on the sheet or on another sheet of its tree.
     */
    @Transactional
    public Person addAlias(String by, String sheetId, Long id, String alias) {
        String trimmed = alias == null ? "" : alias.strip();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an alias.");
        }
        Person person = people.findWithLockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (nameOrAliasInUse(sheetId, trimmed)) {
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

    /**
     * Whether the name or alias is, ignoring case, a name or an alias of anyone on the sheet or on another sheet of
     * its tree: its ancestors, and its descendants and theirs.
     */
    @Transactional(readOnly = true)
    public boolean nameOrAliasInUse(String sheetId, String nameOrAlias) {
        return people.nameOrAliasInUse(sheetId, nameOrAlias) || inUseOn(tree(sheetId), nameOrAlias);
    }

    /**
     * The ids of the people on the sheet who clash: whose name or an alias is, ignoring case, a name or an alias on
     * another sheet of its tree (they came to clash before such names were refused, or as the tree was linked).
     */
    @Transactional(readOnly = true)
    public Set<Long> clashing(String sheetId) {
        Set<String> tree = tree(sheetId);
        if (tree.isEmpty()) {
            return Set.of();
        }
        Set<String> taken = new HashSet<>();
        for (Person p : people.findBySheetIdIn(tree)) {
            taken.add(p.getName().toLowerCase(Locale.ROOT));
            p.getAliases().forEach(a -> taken.add(a.toLowerCase(Locale.ROOT)));
        }
        Set<Long> clashing = new HashSet<>();
        for (Person p : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            if (taken.contains(p.getName().toLowerCase(Locale.ROOT))
                    || p.getAliases().stream().anyMatch(a -> taken.contains(a.toLowerCase(Locale.ROOT)))) {
                clashing.add(p.getId());
            }
        }
        return clashing;
    }

    /**
     * The other sheets of the sheet's tree, among the sheets that exist: its parent, their parent and so on, and the
     * sheets naming it as parent, the ones naming those, and so on.
     */
    private Set<String> tree(String sheetId) {
        Set<String> tree = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>(Set.of(sheetId));
        String at = sheetRepository.findById(sheetId).map(Sheet::getParentId).orElse(null);
        while (at != null && seen.add(at)) {
            Sheet parent = sheetRepository.findById(at).orElse(null);
            if (parent == null) {
                break;
            }
            tree.add(at);
            at = parent.getParentId();
        }
        ArrayDeque<String> toVisit = new ArrayDeque<>(List.of(sheetId));
        Set<String> below = new HashSet<>(Set.of(sheetId));
        while (!toVisit.isEmpty()) {
            for (Sheet child : sheetRepository.findByParentIdOrderByIdAsc(toVisit.poll())) {
                if (below.add(child.getId())) {
                    toVisit.add(child.getId());
                    if (!child.getId().equals(sheetId)) {
                        tree.add(child.getId());
                    }
                }
            }
        }
        tree.remove(sheetId);
        return tree;
    }

    private boolean inUseOn(Set<String> sheetIds, String nameOrAlias) {
        return !sheetIds.isEmpty() && people.nameOrAliasInUseOn(sheetIds, nameOrAlias);
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
