package net.officefloor.hq.app.person;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.SheetTag;
import net.officefloor.hq.app.claim.SheetTagRepository;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the people on a sheet. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final PersonPatronRepository patrons;
    private final PersonAliasRepository aliases;
    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final SheetService sheetService;
    private final SheetTagRepository tags;
    private final PersonSnapshotRepository snapshots;

    public PersonService(PersonRepository people, PersonPatronRepository patrons, PersonAliasRepository aliases,
            SheetRepository sheets, Audit audit, RollupService rollup, SheetService sheetService,
            SheetTagRepository tags, PersonSnapshotRepository snapshots) {
        this.snapshots = snapshots;
        this.tags = tags;
        this.aliases = aliases;
        this.sheetService = sheetService;
        this.people = people;
        this.patrons = patrons;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms) {
        return add(sheetId, user, name, grade, terms, null);
    }

    /** Add the person with their contact (blank: none), refused when another person on the sheet has it. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact) {
        return add(sheetId, user, name, grade, terms, contact, null);
    }

    /** Add the person with their contact and their comma separated tags (blank: none). */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact,
            String tagText) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException(sheetId);
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        if (people.existsBySheetIdAndNameKey(sheetId, Person.key(name))
                || aliases.existsBySheetIdAndAliasKey(sheetId, Person.key(name))
                || usedInTree(sheetId, Person.key(name), null)) {
            throw new PersonNameTakenException(sheetId, name.trim());
        }
        String contactKey = Person.contactKey(contact);
        if (contactKey != null && people.existsBySheetIdAndContact(sheetId, contactKey)) {
            throw new PersonContactTakenException(sheetId, contactKey);
        }
        Person person = new Person(sheetId, name, grade);
        person.setTerms(terms);
        person.setContact(contactKey);
        person.setTags(tagged(sheetId, tagText));
        person.setAddedOn(sheetService.today(sheetId));
        person.setPosition(people.maxPosition(sheetId) + 1);
        Person saved = save(person);
        audit.recordOnCommit(record("PERSON_ADDED", user, saved, grade)
                + (terms != null ? " terms=" + terms : "")
                + (contactKey != null ? " contact=" + contactKey : ""));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    @Transactional
    /**
     * Save the person. The grade is recorded from the from-date (null: the sheet's today), keeping the grades
     * already recorded; a save leaving the grade as it is in force today, with no from-date, records none. The
     * terms (null: none) and the contact (blank: none) replace the person's own.
     */
    public Person update(String sheetId, String user, Long id, Long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        return update(sheetId, user, id, version, name, grade, gradeFrom, terms, contact, null);
    }

    /** Save the person as above, replacing their tags with the comma separated ones (blank: none; null: kept). */
    @Transactional
    public Person update(String sheetId, String user, Long id, Long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact, String tagText) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        if (version != null && version != person.getVersion()) {
            throw new PersonVersionConflictException(sheetId, id);
        }
        if (people.existsBySheetIdAndNameKeyAndIdNot(sheetId, Person.key(name), id)
                || aliases.existsBySheetIdAndAliasKey(sheetId, Person.key(name))
                || usedInTree(sheetId, Person.key(name), null)) {
            throw new PersonNameTakenException(sheetId, name.trim());
        }
        // A person whose alias clashes with a name or alias elsewhere in the tree cannot be saved until it does not.
        for (PersonAlias alias : aliases.findByPersonId(id)) {
            if (usedInTree(sheetId, alias.getAliasKey(), null)) {
                throw new PersonNameTakenException(sheetId, alias.getAlias());
            }
        }
        String contactKey = Person.contactKey(contact);
        if (contactKey != null && people.existsBySheetIdAndContactAndIdNot(sheetId, contactKey, id)) {
            throw new PersonContactTakenException(sheetId, contactKey);
        }
        LocalDate today = sheetService.today(sheetId);
        Integer termsBefore = person.getTerms();
        String contactBefore = person.getContact();
        person.setName(name);
        person.setTerms(terms);
        person.setContact(contactKey);
        if (tagText != null) {
            person.setTags(tagged(sheetId, tagText));
        }
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.setGrade(grade, gradeFrom != null ? gradeFrom : today);
        }
        Person saved = save(person);
        audit.recordOnCommit(record("PERSON_EDITED", user, saved, saved.gradeOn(today))
                + (Objects.equals(termsBefore, terms) ? "" : " terms=" + (terms != null ? terms : ""))
                + (Objects.equals(contactBefore, contactKey) ? "" : " contact=" + (contactKey != null ? contactKey : "")));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * The tags of comma separated text, each trimmed and lower-cased, without repeats, sorted and joined by commas
     * (null when there are none), each noted as used on the sheet.
     */
    String tagged(String sheetId, String text) {
        List<String> list = SheetTag.parse(text);
        noteTags(sheetId, list);
        return list.isEmpty() ? null : String.join(",", list);
    }

    /** Note each tag as used on the sheet. */
    void noteTags(String sheetId, List<String> list) {
        for (String tag : list) {
            if (!tags.existsBySheetIdAndTag(sheetId, tag)) {
                tags.save(new SheetTag(sheetId, tag));
            }
        }
    }

    /** Retire the person from the date, or from the sheet's today when none is given. */
    @Transactional
    public Person retire(String sheetId, String user, Long id, LocalDate from) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        LocalDate today = sheetService.today(sheetId);
        person.retire(from != null ? from : today, today);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " from=" + saved.getRetiredFrom());
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** Unretire the person on the sheet's today, keeping the retirement as a past one; refused when not retired. */
    @Transactional
    public Person unretire(String sheetId, String user, Long id) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        if (!person.isRetired()) {
            throw new UnretireRefusedException(person.getName() + " is not retired");
        }
        LocalDate today = sheetService.today(sheetId);
        person.unretire(today);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_UNRETIRED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " on=" + today);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Swap the person with the one above them in the list (up) or below them (down); refused for the top person moving
     * up or the bottom one moving down.
     */
    @Transactional
    public Person move(String sheetId, String user, Long id, boolean up) {
        List<Person> ordered = list(sheetId);
        int at = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(id)) {
                at = i;
            }
        }
        if (at < 0) {
            throw new PersonNotFoundException(sheetId, id);
        }
        int to = up ? at - 1 : at + 1;
        if (to < 0 || to >= ordered.size()) {
            throw new PersonMoveRefusedException(ordered.get(at).getName() + " is already at the "
                    + (up ? "top" : "bottom") + " of the list");
        }
        // Positions are renumbered from 1 in list order, with the two people swapped.
        Collections.swap(ordered, at, to);
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getPosition() != i + 1) {
                ordered.get(i).setPosition(i + 1);
                save(ordered.get(i));
            }
        }
        Person moved = ordered.get(to);
        audit.recordOnCommit("PERSON_MOVED sheet=" + sheetId + " by=" + user + " name=" + moved.getName()
                + " position=" + (to + 1));
        return moved;
    }

    /** Hold the person from the date (null: the sheet's today) to the date (null: it does not end). */
    @Transactional
    public Person hold(String sheetId, String user, Long id, LocalDate from, LocalDate to) {
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        LocalDate fromDate = from != null ? from : sheetService.today(sheetId);
        if (to != null && to.isBefore(fromDate)) {
            throw new HoldRefusedException("A hold of " + person.getName() + " cannot end before it starts");
        }
        person.addHold(fromDate, to, sheetService.today(sheetId));
        Person saved = save(person);
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " from=" + fromDate);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** The ids of each person's patrons, by person id, in the order they were named. */
    public Map<Long, List<Long>> patronIds(String sheetId) {
        Map<Long, List<Long>> byPerson = new HashMap<>();
        for (PersonPatron patron : patrons.findBySheetIdOrderByIdAsc(sheetId)) {
            byPerson.computeIfAbsent(patron.getPersonId(), id -> new ArrayList<>()).add(patron.getPatronId());
        }
        return byPerson;
    }

    /**
     * The shares of a person's patrons, in the order they were named: 100.00 split equally, each rounded half to even
     * to two decimals, with the remainder (100.00 minus the shares added up) given to the first-named patron.
     */
    public static List<BigDecimal> patronShares(int count) {
        if (count == 0) {
            return List.of();
        }
        BigDecimal whole = new BigDecimal("100.00");
        BigDecimal share = whole.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_EVEN);
        List<BigDecimal> shares = new ArrayList<>(Collections.nCopies(count, share));
        shares.set(0, whole.subtract(share.multiply(BigDecimal.valueOf(count - 1))));
        return shares;
    }

    /** The ids of the person's patrons in the order they were named. */
    public List<Long> patronIdsOf(Long personId) {
        return patrons.findByPersonIdOrderByIdAsc(personId).stream().map(PersonPatron::getPatronId).toList();
    }

    /** Name another person on the sheet as the person's patron: not themselves, and not one already named. */
    @Transactional
    public Person addPatron(String sheetId, String user, Long id, Long patronId) {
        Person person = people.findForUpdate(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, patronId));
        if (person.getId().equals(patron.getId())) {
            throw new PatronRefusedException(person.getName() + " cannot be their own patron");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw new PatronRefusedException(patron.getName() + " is already a patron of " + person.getName());
        }
        try {
            patrons.saveAndFlush(new PersonPatron(sheetId, person.getId(), patron.getId()));
        } catch (DataIntegrityViolationException e) {
            // Another user named the same patron at the same moment: the unique index refused it.
            throw new PatronRefusedException(patron.getName() + " is already a patron of " + person.getName());
        }
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " patron=" + patron.getName());
        return person;
    }

    /** The person on the sheet with the name or the alias, as names are compared. */
    public Optional<Person> findByNameOrAlias(String sheetId, String name) {
        String key = Person.key(name);
        return people.findBySheetIdAndNameKey(sheetId, key)
                .or(() -> aliases.findFirstBySheetIdAndAliasKeyOrderByIdAsc(sheetId, key)
                        .flatMap(a -> people.findByIdAndSheetId(a.getPersonId(), sheetId)));
    }

    /** Each person's aliases, by person id, sorted as names are compared. */
    public Map<Long, List<String>> aliases(String sheetId) {
        Map<Long, List<String>> byPerson = new HashMap<>();
        aliases.findBySheetId(sheetId).stream().sorted(ALIAS_ORDER)
                .forEach(a -> byPerson.computeIfAbsent(a.getPersonId(), id -> new ArrayList<>()).add(a.getAlias()));
        return byPerson;
    }

    /** The snapshot lines of the sheet's people, by person id, each oldest first. */
    public Map<Long, List<String>> snapshotLines(String sheetId) {
        Map<Long, List<String>> byPerson = new HashMap<>();
        snapshots.findBySheetIdOrderByIdAsc(sheetId)
                .forEach(s -> byPerson.computeIfAbsent(s.getPersonId(), id -> new ArrayList<>()).add(s.line()));
        return byPerson;
    }

    /** The person's snapshot lines, oldest first. */
    public List<String> snapshotLinesOf(Long personId) {
        return snapshots.findByPersonIdOrderByIdAsc(personId).stream().map(PersonSnapshot::line).toList();
    }

    /** The person's aliases, sorted as names are compared. */
    public List<String> aliasesOf(Long personId) {
        return aliases.findByPersonId(personId).stream().sorted(ALIAS_ORDER).map(PersonAlias::getAlias).toList();
    }

    /** Give the person an alias: not blank, and not equal to any name or alias on the sheet. */
    @Transactional
    public Person addAlias(String sheetId, String user, Long id, String alias) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new PersonNotFoundException(sheetId, id));
        if (alias == null || alias.isBlank()) {
            throw new AliasRefusedException("An alias cannot be blank");
        }
        String key = Person.key(alias);
        if (people.existsBySheetIdAndNameKey(sheetId, key) || aliases.existsBySheetIdAndAliasKey(sheetId, key)
                || usedInTree(sheetId, key, null)) {
            throw new AliasRefusedException("The name " + alias.trim() + " is already in use on the sheet");
        }
        PersonAlias saved;
        try {
            saved = aliases.saveAndFlush(new PersonAlias(sheetId, person.getId(), alias));
        } catch (DataIntegrityViolationException e) {
            // Another user took the alias at the same moment: the unique index refused it.
            throw new AliasRefusedException("The name " + alias.trim() + " is already in use on the sheet");
        }
        audit.recordOnCommit("PERSON_ALIAS_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " alias=" + saved.getAlias());
        return person;
    }

    /**
     * The other sheets of the sheet's tree, each once: its ancestors, and its descendants and theirs, among the sheets
     * that exist. A parent loop never brings back the sheet.
     */
    public Set<String> treeOf(String sheetId) {
        Set<String> tree = new HashSet<>();
        String parent = sheets.findById(sheetId).map(Sheet::getParent).orElse(null);
        while (parent != null && !parent.equals(sheetId) && tree.add(parent)) {
            parent = sheets.findById(parent).map(Sheet::getParent).orElse(null);
        }
        List<String> below = new ArrayList<>(List.of(sheetId));
        for (int i = 0; i < below.size(); i++) {
            for (Sheet child : sheets.findByParentOrderByIdAsc(below.get(i))) {
                if (!below.contains(child.getId())) {
                    below.add(child.getId());
                }
            }
        }
        tree.addAll(below.subList(1, below.size()));
        tree.retainAll(sheets.findAllById(tree).stream().map(Sheet::getId).toList());
        return tree;
    }

    /**
     * Whether another sheet of the sheet's tree has the key as a name or an alias, leaving out the person with the id
     * (null: none) and their aliases.
     */
    public boolean usedInTree(String sheetId, String key, Long exceptPersonId) {
        Set<String> tree = treeOf(sheetId);
        long except = exceptPersonId == null ? -1L : exceptPersonId;
        return !tree.isEmpty() && (people.existsBySheetIdInAndNameKeyAndIdNot(tree, key, except)
                || aliases.existsBySheetIdInAndAliasKeyAndPersonIdNot(tree, key, except));
    }

    /** The names and aliases, as names are compared, on the other sheets of the sheet's tree. */
    public Set<String> keysInTree(String sheetId) {
        Set<String> tree = treeOf(sheetId);
        Set<String> keys = new HashSet<>();
        if (!tree.isEmpty()) {
            keys.addAll(people.findNameKeysBySheetIdIn(tree));
            keys.addAll(aliases.findAliasKeysBySheetIdIn(tree));
        }
        return keys;
    }

    /** Whether the person's name or one of their aliases is among the keys used on the other sheets of the tree. */
    public static boolean clashing(Person person, List<String> aliasesOf, Set<String> keysInTree) {
        return keysInTree.contains(Person.key(person.getName()))
                || aliasesOf.stream().anyMatch(a -> keysInTree.contains(Person.key(a)));
    }

    private static final Comparator<PersonAlias> ALIAS_ORDER =
            Comparator.comparing(PersonAlias::getAliasKey).thenComparing(PersonAlias::getAlias);

    private static String record(String event, String user, Person person, String grade) {
        return event + " sheet=" + person.getSheetId() + " by=" + user
                + " name=" + person.getName() + " grade=" + grade;
    }

    private Person save(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            // Another user took the name or the contact at the same moment: a unique index refused it.
            if (person.getContact() != null && !people.existsBySheetIdAndNameKeyAndIdNot(person.getSheetId(),
                    Person.key(person.getName()), person.getId() == null ? -1L : person.getId())) {
                throw new PersonContactTakenException(person.getSheetId(), person.getContact());
            }
            throw new PersonNameTakenException(person.getSheetId(), person.getName());
        } catch (ObjectOptimisticLockingFailureException e) {
            // Another user saved the person at the same moment.
            throw new PersonVersionConflictException(person.getSheetId(), person.getId());
        }
    }
}
