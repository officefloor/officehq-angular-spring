package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.UsedTag;
import net.officefloor.hq.app.claim.UsedTagRepository;
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
 * A retired grade cannot be chosen: not for a new person, nor by an edit that records a grade (people
 * already on it keep it).
 * A
 * name is unique on the sheet, compared without regard to case (names arrive trimmed). A person can
 * be retired from a date (the sheet's today when none is given); they count as retired at once. A
 * retired person can be unretired on the sheet's today; the retirement is kept as a past one. A
 * person can be put on hold over a span of dates, and may have several holds over time. A person can name other people on the sheet as
 * their patrons, one at a time: not themselves, and not the same person twice. A user can watch a
 * person, and stop watching them; each user's watching is their own. A person may have their own
 * terms, in days; a claim's due-by takes those in force on its date before the terms of the person's
 * grade and the sheet's. Terms saved with a from-date are recorded from that date, keeping those already
 * recorded (replacing any from that same date); saved without one, they are recorded from the beginning
 * of time, unless they leave the terms in force on the sheet's today as they are.
 * A person may have a contact (arriving trimmed and lower-cased, null for none), unique on the sheet.
 * A person may have tags (arriving trimmed, lower-cased, without repeats and sorted), each kept as used on the sheet.
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
    private final UsedTagRepository usedTags;

    public PersonService(PersonRepository people, SheetService sheets, Audit audit, RollupService rollup,
            PatronRepository patrons, RoleService roles, WatchRepository watches, UsedTagRepository usedTags) {
        this.usedTags = usedTags;
        this.roles = roles;
        this.watches = watches;
        this.people = people;
        this.patrons = patrons;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
    }

    /** The sheet's today: the day a person's terms shown are those in force on. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.today(sheetId);
    }

    /** The grade a new person on the sheet takes when none is given: its default grade, else A. */
    public String defaultGrade(String sheetId) {
        return sheets.defaultGrade(sheetId);
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
        return add(sheetId, user, name, grade, terms, contact, List.of());
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact,
            List<String> tags) {
        return add(sheetId, user, name, grade, terms, null, contact, tags);
    }

    /** Add the person; their terms are recorded from {@code termsFrom}, or from the beginning of time when null. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, LocalDate termsFrom,
            String contact, List<String> tags) {
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
        refuseRetiredGrade(sheetId, grade);
        LocalDate today = sheets.today(sheetId);
        Person person = new Person(sheetId, name, grade, today);
        if (termsFrom != null) {
            person.setTermsFrom(termsFrom, terms);
        } else {
            person.setTerms(terms);
        }
        person.setContact(contact);
        person.setTags(tags);
        person.setPosition(people.maxPosition(sheetId) + 1);
        Person added = save(person);
        useTags(sheetId, added);
        Integer termsNow = added.termsOn(today);
        audit.recordOnCommit(record("PERSON_ADDED", sheetId, user, added, today)
                + (termsNow != null ? " terms=" + termsNow : "") + (contact != null ? " contact=" + contact : ""));
        rollup.recordChanges(sheetId, user, before);
        return added;
    }

    /** Edit the person, keeping their tags. */
    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        return update(sheetId, user, id, version, name, grade, gradeFrom, terms, contact, null);
    }

    /** Edit the person; {@code tags} null keeps the ones they have. */
    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact, List<String> tags) {
        return update(sheetId, user, id, version, name, grade, gradeFrom, terms, null, contact, tags);
    }

    /**
     * Edit the person; {@code tags} null keeps the ones they have. The terms are recorded from
     * {@code termsFrom}; with none, from the beginning of time unless the terms in force on the
     * sheet's today are left as they are.
     */
    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, LocalDate termsFrom, String contact, List<String> tags) {
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
        boolean gradeChosen = gradeFrom != null || !grade.equals(person.gradeOn(today));
        if (gradeChosen) {
            refuseRetiredGrade(sheetId, grade);
        }
        boolean termsChanged = termsFrom != null || !Objects.equals(terms, person.termsOn(today));
        String contactBefore = person.getContact();
        person.setName(name);
        if (termsFrom != null) {
            person.setTermsFrom(termsFrom, terms);
        } else if (termsChanged) {
            person.setTerms(terms);
        }
        person.setContact(contact);
        if (tags != null) {
            person.setTags(tags);
        }
        if (gradeChosen) {
            person.setGradeFrom(gradeFrom != null ? gradeFrom : today, grade, today);
        }
        Person saved = save(person);
        useTags(sheetId, saved);
        audit.recordOnCommit(record("PERSON_EDITED", sheetId, user, saved, today)
                + (termsChanged ? " terms=" + Objects.toString(saved.termsOn(today), "") : "")
                + (Objects.equals(contactBefore, contact) ? "" : " contact=" + (contact != null ? contact : "")));
        rollup.recordChanges(sheetId, user, before);
        return saved;
    }

    /** Keep each of the person's tags as used on the sheet. */
    private void useTags(String sheetId, Person person) {
        for (String tag : person.getTags()) {
            if (!usedTags.existsBySheetIdAndTag(sheetId, tag)) {
                usedTags.saveAndFlush(new UsedTag(sheetId, tag));
            }
        }
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
                gradeFrom, p.termsOn(sheets.today(sheetId)), p.getContact())).toList();
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
     * to-date; refused (400) when the to-date is before the from-date. Their claims still count in every
     * total; while the hold is in force on the sheet's today they take no cut of a pool.
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

    /**
     * Flag the person with a reason until a date (a steward only, 403 for anyone else; 400 for a
     * blank reason). While the sheet's today is on or before the date, only a steward may open a
     * claim of theirs.
     */
    @Transactional
    public Person flag(String sheetId, String user, long id, String reason, LocalDate until) {
        roles.refuseUnlessSteward(sheetId, user);
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a reason for the flag.");
        }
        Person person = people.lockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        person.flag(reason, until);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_FLAGGED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " reason=" + reason + " until=" + until);
        return saved;
    }

    /**
     * Give the person an approver (a blank name: none), who may approve or reject their PENDING
     * claims as a steward may; refused (403) for a viewer.
     */
    @Transactional
    public Person setApprover(String sheetId, String user, long id, String approver) {
        roles.refuseViewer(sheetId, user);
        Person person = people.lockByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        person.setApprover(approver.isEmpty() ? null : approver);
        Person saved = save(person);
        audit.recordOnCommit("APPROVER_SET sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " user=" + approver);
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

    /** A retired grade cannot be chosen for a new person or in an edit (409). */
    private void refuseRetiredGrade(String sheetId, String grade) {
        if (sheets.isRetiredGrade(sheetId, grade)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The grade " + grade + " is retired.");
        }
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
