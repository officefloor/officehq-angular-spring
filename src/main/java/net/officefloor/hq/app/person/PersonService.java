package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The people on a sheet: listed by position (new people at the end, moved up or down one place), added and edited by name and grade. A
 * person's grade is dated: the first applies from the beginning of time, and each edit records the
 * grade from its from-date (the sheet's today when none is given), keeping those already recorded;
 * an edit leaving the grade in force on the sheet's today as it is, with no from-date, records none.
 * A
 * name is unique on the sheet, compared without regard to case (names arrive trimmed). A person can
 * be retired from a date (the sheet's today when none is given); they count as retired at once. A
 * retired person can be unretired on the sheet's today; the retirement is kept as a past one. A
 * person can be put on hold over a span of dates, and may have several holds over time. A person can name other people on the sheet as
 * their patrons, one at a time: not themselves, and not the same person twice. A user can watch a
 * person, and stop watching them; each user's watching is their own. A person may have their own
 * terms, in days; a claim's due-by takes them before the terms of the person's grade and the sheet's.
 * A person may have a contact (arriving trimmed and lower-cased, null for none), unique on the sheet.
 * A person may have aliases (arriving trimmed), sharing the names' uniqueness: an alias equals no name
 * or alias on the sheet, and no name equals an alias.
 * Nor may a name or alias be used on a sheet when another sheet of its tree (see
 * {@link SheetService#tree}) already uses it as a name or alias. People who already clash so are not
 * changed: they show as clashing, and every edit of them is refused until it renames them to a name
 * that does not clash and leaves them no clashing alias.
 */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final PatronRepository patrons;
    private final RoleService roles;
    private final WatchRepository watches;

    public PersonService(PersonRepository people, SheetService sheets, Audit audit, RollupService rollup,
            PatronRepository patrons, RoleService roles, WatchRepository watches) {
        this.roles = roles;
        this.watches = watches;
        this.people = people;
        this.patrons = patrons;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms) {
        return add(sheetId, user, name, grade, terms, null);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact) {
        roles.refuseViewer(sheetId, user);
        sheets.load(sheetId, user);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, name) || people.existsAliasOnSheet(sheetId, name)
                || usedInTree(sheetId, name, null)) {
            throw nameInUse();
        }
        if (contact != null && people.existsBySheetIdAndContact(sheetId, contact)) {
            throw contactInUse();
        }
        Person person = new Person(sheetId, name, grade, sheets.today(sheetId));
        person.setTerms(terms);
        person.setContact(contact);
        person.setPosition(people.maxPosition(sheetId) + 1);
        Person added = save(person);
        audit.recordOnCommit(record("PERSON_ADDED", sheetId, user, added, sheets.today(sheetId))
                + (terms != null ? " terms=" + terms : "") + (contact != null ? " contact=" + contact : ""));
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        roles.refuseViewer(sheetId, user);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (person.getVersion() != version) {
            throw stale();
        }
        if (people.existsBySheetIdAndNameIgnoreCaseAndIdNot(sheetId, name, id) || people.existsAliasOnSheet(sheetId, name)
                || usedInTree(sheetId, name, null)) {
            throw nameInUse();
        }
        for (String alias : person.getAliases()) {
            if (usedInTree(sheetId, alias, null)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The alias " + alias + " is already in use on a sheet of this sheet's tree.");
            }
        }
        if (contact != null && people.existsBySheetIdAndContactAndIdNot(sheetId, contact, id)) {
            throw contactInUse();
        }
        LocalDate today = sheets.today(sheetId);
        Integer termsBefore = person.getTerms();
        String contactBefore = person.getContact();
        person.setName(name);
        person.setTerms(terms);
        person.setContact(contact);
        if (gradeFrom != null || !grade.equals(person.gradeOn(today))) {
            person.setGradeFrom(gradeFrom != null ? gradeFrom : today, grade, today);
        }
        Person saved = save(person);
        audit.recordOnCommit(record("PERSON_EDITED", sheetId, user, saved, today)
                + (Objects.equals(termsBefore, terms) ? "" : " terms=" + (terms != null ? terms : ""))
                + (Objects.equals(contactBefore, contact) ? "" : " contact=" + (contact != null ? contact : "")));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Whether another sheet of the sheet's tree has a person, other than the one of {@code exceptId}
     * (null for none), with this name or alias, compared without regard to case.
     */
    @Transactional(readOnly = true)
    public boolean usedInTree(String sheetId, String name, Long exceptId) {
        Set<String> tree = sheets.tree(sheetId);
        return !tree.isEmpty() && people.existsNameOrAliasOnSheets(tree, name, exceptId != null ? exceptId : -1L);
    }

    /**
     * The ids of those of the people on the sheet who clash: whose name or an alias another sheet of
     * its tree uses as a name or alias.
     */
    @Transactional(readOnly = true)
    public Set<Long> clashing(String sheetId, Collection<Person> those) {
        Set<String> tree = sheets.tree(sheetId);
        if (tree.isEmpty()) {
            return Set.of();
        }
        Set<String> used = new HashSet<>();
        for (Person other : people.findBySheetIdIn(tree)) {
            used.add(other.getName().toLowerCase(Locale.ROOT));
            other.getAliases().forEach(a -> used.add(a.toLowerCase(Locale.ROOT)));
        }
        Set<Long> clashing = new HashSet<>();
        for (Person person : those) {
            if (used.contains(person.getName().toLowerCase(Locale.ROOT))
                    || person.getAliases().stream().anyMatch(a -> used.contains(a.toLowerCase(Locale.ROOT)))) {
                clashing.add(person.getId());
            }
        }
        return clashing;
    }

    /** Give the person an alias; refused (409) when it equals any name or alias on the sheet or its tree. */
    @Transactional
    public Person addAlias(String sheetId, String user, long id, String alias) {
        roles.refuseViewer(sheetId, user);
        Person person = people.lockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, alias) || people.existsAliasOnSheet(sheetId, alias)
                || usedInTree(sheetId, alias, null)) {
            throw nameInUse();
        }
        person.addAlias(alias);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_ALIAS_ADDED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " alias=" + alias);
        return saved;
    }

    /**
     * Give each of the people a grade from a from-date (the sheet's today when none is given), in list
     * order, exactly as editing each with the person form would (name, terms and contact kept). All or none: a
     * person not on the sheet refuses the lot. A viewer may grade no one: each person is refused and
     * none is graded.
     */
    @Transactional
    public List<Person> bulkGrade(String sheetId, String user, List<Long> ids, String grade, LocalDate gradeFrom) {
        try {
            roles.refuseViewer(sheetId, user);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
                return List.of();
            }
            throw e;
        }
        Set<Long> wanted = new HashSet<>(ids);
        List<Person> chosen = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> wanted.contains(p.getId())).toList();
        if (chosen.size() != wanted.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet.");
        }
        return chosen.stream().map(p -> update(sheetId, user, p.getId(), p.getVersion(), p.getName(), grade,
                gradeFrom, p.getTerms(), p.getContact())).toList();
    }

    /**
     * Retire each of the people from a date (the sheet's today when none is given), in list order,
     * exactly as retiring each from their row would. All or none: a person not on the sheet refuses
     * the lot. A viewer may retire no one: each person is refused and none is retired.
     */
    @Transactional
    public List<Person> bulkRetire(String sheetId, String user, List<Long> ids, LocalDate date) {
        try {
            roles.refuseViewer(sheetId, user);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
                return List.of();
            }
            throw e;
        }
        Set<Long> wanted = new HashSet<>(ids);
        List<Person> chosen = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> wanted.contains(p.getId())).toList();
        if (chosen.size() != wanted.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet.");
        }
        return chosen.stream().map(p -> retire(sheetId, user, p.getId(), date)).toList();
    }

    /**
     * Swap the person with the one above ({@code up}) or below them in the list; the top person
     * cannot move up nor the bottom one down (409).
     */
    @Transactional
    public Person move(String sheetId, String user, long id, boolean up) {
        roles.refuseViewer(sheetId, user);
        sheets.refuseIfArchived(sheetId);
        List<Person> listed = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        int at = -1;
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).getId() == id) {
                at = i;
            }
        }
        if (at < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet.");
        }
        int to = up ? at - 1 : at + 1;
        if (to < 0 || to >= listed.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    up ? "The first person cannot move up." : "The last person cannot move down.");
        }
        // renumber the list as it stands, the two swapped, so equal positions cannot leave them tied
        Person moved = listed.get(at);
        listed.set(at, listed.get(to));
        listed.set(to, moved);
        for (int i = 0; i < listed.size(); i++) {
            Person p = listed.get(i);
            if (p.getPosition() != i + 1) {
                p.setPosition(i + 1);
                save(p);
            }
        }
        audit.recordOnCommit("PERSON_MOVED sheet=" + sheetId + " by=" + user + " name=" + moved.getName()
                + " position=" + (to + 1));
        return moved;
    }

    /**
     * Retire the person from a date, or from the sheet's today when none is given. Once the sheet's
     * today reaches that date their OPEN claims leave every live total, so the figures that move are recorded.
     */
    @Transactional
    public Person retire(String sheetId, String user, long id, LocalDate date) {
        roles.refuseViewer(sheetId, user);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate from = date != null ? date : sheets.today(sheetId);
        person.setRetiredFrom(from, sheets.today(sheetId));
        Person saved = save(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user + " name=" + saved.getName() + " from="
                + from);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Unretire the person on the sheet's today: they are no longer retired, and the retirement is
     * kept as a past one from its from-date to the sheet's today. Refused (409) when they are not
     * retired. Their OPEN claims return to the live totals, so the figures that move are recorded.
     */
    @Transactional
    public Person unretire(String sheetId, String user, long id) {
        roles.refuseViewer(sheetId, user);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (!person.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is not retired.");
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        LocalDate on = sheets.today(sheetId);
        person.unretire(on);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_UNRETIRED sheet=" + sheetId + " by=" + user + " name=" + saved.getName() + " on="
                + on);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /**
     * Put the person on hold from a date (the sheet's today when none is given) to an optional
     * to-date; refused (400) when the to-date is before the from-date. Their OPEN claims dated within
     * the hold leave every live total, so the figures that move are recorded.
     */
    @Transactional
    public Person hold(String sheetId, String user, long id, LocalDate fromDate, LocalDate toDate) {
        roles.refuseViewer(sheetId, user);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person person = people.lockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        LocalDate from = fromDate != null ? fromDate : sheets.today(sheetId);
        if (toDate != null && toDate.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A hold cannot end before it starts.");
        }
        person.addHold(from, toDate, sheets.today(sheetId));
        Person saved = save(person);
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user + " name=" + saved.getName() + " from="
                + from);
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Patron> patrons(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /**
     * Name another person on the sheet as the person's patron; refused (409) when it is the person
     * themselves or already one of their patrons.
     */
    @Transactional
    public Patron addPatron(String sheetId, String user, long id, long patronId) {
        roles.refuseViewer(sheetId, user);
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patron on this sheet."));
        if (person.getId().equals(patron.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be their own patron.");
        }
        if (patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw patronTaken();
        }
        Patron added;
        try {
            added = patrons.saveAndFlush(new Patron(sheetId, person.getId(), patron.getId()));
        } catch (DataIntegrityViolationException e) {
            throw patronTaken();
        }
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user + " name=" + person.getName()
                + " patron=" + patron.getName());
        return added;
    }

    /** The ids of the people the user watches on the sheet. */
    @Transactional(readOnly = true)
    public List<Long> watched(String sheetId, String user) {
        return watches.findBySheetIdAndUserName(sheetId, user).stream().map(Watch::getPersonId).toList();
    }

    /**
     * Start ({@code on}) or stop watching the person as the user; already so, nothing changes and
     * nothing is recorded.
     */
    @Transactional
    public void watch(String sheetId, String user, long id, boolean on) {
        roles.refuseViewer(sheetId, user);
        sheets.refuseIfArchived(sheetId);
        Person person = people.lockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        var existing = watches.findByPersonIdAndUserName(person.getId(), user);
        if (on == existing.isPresent()) {
            return;
        }
        if (on) {
            watches.saveAndFlush(new Watch(sheetId, person.getId(), user));
        } else {
            watches.delete(existing.get());
            watches.flush();
        }
        audit.recordOnCommit((on ? "PERSON_WATCHED" : "PERSON_UNWATCHED") + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName());
    }

    private static ResponseStatusException patronTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That person is already a patron.");
    }

    /** A person's record, with the grade in force on the sheet's today. */
    private static String record(String event, String sheetId, String user, Person person, LocalDate today) {
        return event + " sheet=" + sheetId + " by=" + user + " name=" + person.getName() + " grade="
                + person.gradeOn(today);
    }

    /**
     * Save, refusing a name another user took on the sheet since it was checked, and an edit another
     * user saved over since the person was read.
     */
    private Person save(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            throw person.getContact() != null && String.valueOf(e.getMostSpecificCause().getMessage())
                    .toUpperCase().contains("PERSON_SHEET_CONTACT") ? contactInUse() : nameInUse();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw stale();
        }
    }

    /** The edit was made from a version of the person that another user has since saved over. */
    private static ResponseStatusException stale() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Someone else has saved this person since it was opened.");
    }

    private static ResponseStatusException contactInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That contact is already in use on this sheet.");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet or its tree.");
    }
}
