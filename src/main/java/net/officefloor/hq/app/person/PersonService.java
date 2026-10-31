package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollup;
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
    private final SheetService sheets;
    private final Audit audit;
    private final Rollup rollup;
    private final SheetRepository sheetStore;

    public PersonService(PersonRepository people, SheetService sheets, Audit audit, Rollup rollup,
            SheetRepository sheetStore) {
        this.people = people;
        this.sheetStore = sheetStore;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** Everyone on the sheet, in list order. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
    }

    /** Add a person on no terms of their own. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade) {
        return add(sheetId, user, name, grade, null);
    }

    /** Add a person with their own terms (null: none); the name is stored trimmed and must not already be in use
     *  on the sheet. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms) {
        return add(sheetId, user, name, grade, terms, null);
    }

    /** Add a person with their own terms (null: none) and contact (stored trimmed and lower-cased; blank or null:
     *  none); the name and the contact must not already be in use on the sheet, nor the name, as a name or an alias,
     *  on another sheet of its tree. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact) {
        sheets.load(sheetId, user);
        String trimmed = name.trim();
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, trimmed)
                || people.findBySheetIdAndAliasIgnoreCase(sheetId, trimmed).isPresent()
                || usedInTree(sheetId).contains(lower(trimmed))) {
            throw nameInUse();
        }
        String reach = contactOf(contact);
        if (reach != null && people.existsBySheetIdAndContact(sheetId, reach)) {
            throw contactInUse();
        }
        Rollup.Figures before = rollup.of(sheetId);
        Person person = new Person(sheetId, trimmed, grade);
        person.setTerms(terms);
        person.setContact(reach);
        person.setPosition(people.findMaxPositionBySheetId(sheetId) + 1);
        Person added = save(person);
        audit.recordChangeOnCommit(sheetId, user, entry("PERSON_ADDED", user, added, grade)
                + (terms != null ? " terms=" + terms : "") + (reach != null ? " contact=" + reach : ""));
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    /** The names and aliases, lower-cased, used on the other sheets of the sheet's tree: its ancestors and its
     *  descendants, among the sheets that exist. */
    @Transactional(readOnly = true)
    public Set<String> usedInTree(String sheetId) {
        List<String> tree = new ArrayList<>(sheetStore.ancestorsOf(sheetId, Integer.MAX_VALUE));
        tree.addAll(sheetStore.descendantsOf(sheetId));
        tree.remove(sheetId);
        Set<String> used = new HashSet<>();
        if (!tree.isEmpty()) {
            used.addAll(people.findLowerNamesBySheetIdIn(tree));
            used.addAll(people.findLowerAliasesBySheetIdIn(tree));
        }
        return used;
    }

    /** Whether the person's name or any alias is among {@code used} (lower-cased names and aliases). */
    public static boolean clashes(Person person, Set<String> used) {
        return used.contains(lower(person.getName()))
                || person.getAliases().stream().anyMatch(a -> used.contains(lower(a)));
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** The sheet's today, or null for a sheet never loaded. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
    }

    /**
     * Edit a person, as loaded at {@code version}: refused when someone has saved them since. The grade is
     * recorded from {@code gradeFrom} (null: the sheet's today), keeping the grades already recorded from
     * other dates; a save leaving the grade as in force on the sheet's today, with no from-date, records none.
     * The person's own terms become {@code terms} (null: none); their contact is kept.
     */
    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        return update(sheetId, user, id, version, name, grade, gradeFrom, terms, person.getContact(), false);
    }

    /**
     * Edit a person as {@link #update(String, String, long, long, String, String, LocalDate, Integer)} does, their
     * contact becoming {@code contact} (stored trimmed and lower-cased; blank or null: none): refused when another
     * person on the sheet has it. As an edit through the person form, it is also refused while the new name or any
     * of the person's aliases is used on another sheet of the sheet's tree.
     */
    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        return update(sheetId, user, id, version, name, grade, gradeFrom, terms, contact, true);
    }

    private Person update(String sheetId, String user, long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact, boolean throughForm) {
        LocalDate today = sheets.load(sheetId, user).getToday();
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (person.getVersion() != version) {
            throw changedSince();
        }
        String trimmed = name.trim();
        if (people.existsBySheetIdAndNameIgnoreCaseAndIdNot(sheetId, trimmed, id)
                || people.findBySheetIdAndAliasIgnoreCase(sheetId, trimmed).isPresent()) {
            throw nameInUse();
        }
        if (throughForm) {
            Set<String> used = usedInTree(sheetId);
            if (used.contains(lower(trimmed)) || person.getAliases().stream().anyMatch(a -> used.contains(lower(a)))) {
                throw nameInUse();
            }
        }
        String reach = contactOf(contact);
        if (reach != null && people.existsBySheetIdAndContactAndIdNot(sheetId, reach, id)) {
            throw contactInUse();
        }
        Rollup.Figures before = rollup.of(sheetId);
        person.setName(trimmed);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.setGradeFrom(gradeFrom != null ? gradeFrom : today, grade);
        }
        boolean termsChanged = !Objects.equals(person.getTerms(), terms);
        person.setTerms(terms);
        boolean contactChanged = !Objects.equals(person.getContact(), reach);
        person.setContact(reach);
        Person edited = save(person);
        audit.recordChangeOnCommit(sheetId, user, entry("PERSON_EDITED", user, edited, edited.gradeOn(today))
                + (termsChanged ? " terms=" + (terms != null ? terms : "") : "")
                + (contactChanged ? " contact=" + (reach != null ? reach : "") : ""));
        rollup.recordChanges(sheetId, user, before);
        return edited;
    }

    /** Retire a person from a date; a missing date takes the sheet's today. They count as retired at once; the live
     *  totals move with it once the sheet's today reaches the date. */
    @Transactional
    public Person retire(String sheetId, String user, long id, LocalDate from) {
        LocalDate today = sheets.load(sheetId, user).getToday();
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        Rollup.Figures before = rollup.of(sheetId);
        person.setRetiredFrom(from != null ? from : today);
        Person retired = save(person);
        audit.recordChangeOnCommit(sheetId, user, "PERSON_RETIRED sheet=" + sheetId + " by=" + user
                + " name=" + retired.getName() + " from=" + retired.getRetiredFrom());
        rollup.recordChanges(sheetId, user, before);
        return retired;
    }

    /** Unretire a retired person on the sheet's today: the retirement is kept as a past one, to today. Anyone not
     *  retired is refused. */
    @Transactional
    public Person unretire(String sheetId, String user, long id) {
        LocalDate today = sheets.load(sheetId, user).getToday();
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (!person.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a retired person can be unretired");
        }
        Rollup.Figures before = rollup.of(sheetId);
        person.unretire(today);
        Person unretired = save(person);
        audit.recordChangeOnCommit(sheetId, user, "PERSON_UNRETIRED sheet=" + sheetId + " by=" + user
                + " name=" + unretired.getName() + " on=" + today);
        rollup.recordChanges(sheetId, user, before);
        return unretired;
    }

    /** Put the person on hold from {@code from} (null: the sheet's today) to {@code to} (null: no end), alongside
     *  any holds they have already; the live totals move with it. A to-date before the from-date is refused. */
    @Transactional
    public Person hold(String sheetId, String user, long id, LocalDate from, LocalDate to) {
        LocalDate today = sheets.load(sheetId, user).getToday();
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        LocalDate start = from != null ? from : today;
        if (to != null && to.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A hold cannot end before it starts");
        }
        Rollup.Figures before = rollup.of(sheetId);
        person.addHold(new Hold(start, to));
        Person saved = save(person);
        audit.recordChangeOnCommit(sheetId, user, "PERSON_HELD sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " from=" + start);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** Give the person an alias, stored trimmed: refused when blank, or when it is, without regard to case, any
     *  name or alias on the sheet or on another sheet of its tree. */
    @Transactional
    public Person addAlias(String sheetId, String user, long id, String alias) {
        sheets.load(sheetId, user);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        String trimmed = alias == null ? "" : alias.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An alias cannot be blank");
        }
        if (people.findBySheetIdAndNameOrAliasIgnoreCase(sheetId, trimmed).isPresent()
                || usedInTree(sheetId).contains(lower(trimmed))) {
            throw nameInUse();
        }
        person.addAlias(trimmed);
        Person saved = save(person);
        audit.recordChangeOnCommit(sheetId, user, "PERSON_ALIAS_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " alias=" + trimmed);
        return saved;
    }

    /** Swap the person with the one just above them in the list ({@code up}) or just below; refused for the top
     *  person moving up and the bottom one moving down. Everyone on the sheet, in list order, as moved. */
    @Transactional
    public List<Person> move(String sheetId, String user, long id, boolean up) {
        sheets.load(sheetId, user);
        // The sheet is locked so moves on it are made one at a time, each from the list as the last left it.
        sheetStore.findLockedById(sheetId).orElseThrow();
        List<Person> listed = new ArrayList<>(people.findBySheetIdOrderByPositionAscIdAsc(sheetId));
        int at = -1;
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).getId() == id) {
                at = i;
            }
        }
        if (at < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet");
        }
        int to = up ? at - 1 : at + 1;
        if (to < 0 || to >= listed.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    up ? "The top person cannot move up" : "The bottom person cannot move down");
        }
        Collections.swap(listed, at, to);
        Person moved = listed.get(to);
        // The whole list is renumbered from 1, so positions stay apart however people have come and gone.
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).getPosition() != i + 1) {
                people.setPosition(listed.get(i).getId(), i + 1);
            }
        }
        audit.recordChangeOnCommit(sheetId, user, "PERSON_MOVED sheet=" + sheetId + " by=" + user
                + " name=" + moved.getName() + " position=" + (to + 1));
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
    }

    private static String entry(String event, String user, Person person, String grade) {
        return event + " sheet=" + person.getSheetId() + " by=" + user
                + " name=" + person.getName() + " grade=" + grade;
    }

    private Person save(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException raced) {
            // Another user took the same name at the same moment: theirs stands.
            throw nameInUse();
        } catch (ObjectOptimisticLockingFailureException raced) {
            // Another user saved the same person at the same moment: theirs stands.
            throw changedSince();
        }
    }

    private static ResponseStatusException changedSince() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Someone else has saved this person since it was opened");
    }

    /** A contact as stored: trimmed and lower-cased; null when blank or not given. */
    private static String contactOf(String contact) {
        if (contact == null || contact.isBlank()) {
            return null;
        }
        return contact.trim().toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException contactInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That contact is already in use on this sheet");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet");
    }
}
