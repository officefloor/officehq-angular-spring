package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.claim.SheetTag;
import net.officefloor.hq.app.claim.SheetTagRepository;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.setting.Role;
import net.officefloor.hq.app.setting.SettingService;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.beans.factory.ObjectProvider;
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
    private final PersonAliasRepository aliases;
    private final SheetService sheets;
    private final NameTree tree;
    private final Rollup rollup;
    private final Audit audit;
    private final SheetTagRepository sheetTags;
    private final ObjectProvider<SettingService> settings;

    public PersonService(PersonRepository people, PersonPatronRepository patrons, PersonWatchRepository watches,
            PersonAliasRepository aliases, SheetService sheets, NameTree tree, Rollup rollup, Audit audit,
            SheetTagRepository sheetTags, ObjectProvider<SettingService> settings) {
        this.people = people;
        this.patrons = patrons;
        this.watches = watches;
        this.aliases = aliases;
        this.sheets = sheets;
        this.tree = tree;
        this.rollup = rollup;
        this.audit = audit;
        this.sheetTags = sheetTags;
        this.settings = settings;
    }

    /** The sheet's people in list order. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findInListOrder(sheetId);
    }

    /** The ids of the sheet's people whose name or an alias is used on another sheet of its tree. */
    @Transactional(readOnly = true)
    public Set<Long> clashingIds(String sheetId) {
        Set<String> elsewhere = tree.keysElsewhere(sheetId, NameTree.NO_ONE);
        return list(sheetId).stream().filter(p -> NameTree.clashes(p, elsewhere)).map(Person::getId)
                .collect(Collectors.toSet());
    }

    /** Whether the person's name or an alias is used on another sheet of their sheet's tree. */
    @Transactional(readOnly = true)
    public boolean isClashing(Person person) {
        return tree.clashes(person);
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
        return add(sheetId, user, name, grade, terms, null);
    }

    /** Add a person to the sheet with a contact (blank or null: none); refused when the name is used as a
     *  name or alias on the sheet or on another sheet of its tree, or when another person on the sheet has
     *  the same contact. */
    @Transactional
    public Person add(String sheetId, String user, String name, Grade grade, Integer terms, String contact) {
        return add(sheetId, user, name, grade, terms, contact, null);
    }

    /** Add a person to the sheet with a contact and tags (comma separated; blank or null: none), as
     *  {@link #add(String, String, String, Grade, Integer, String)}. */
    @Transactional
    public Person add(String sheetId, String user, String name, Grade grade, Integer terms, String contact,
            String tags) {
        return add(sheetId, user, name, grade, terms, null, contact, tags);
    }

    /** Add a person as {@link #add(String, String, String, Grade, Integer, String, String)}, their terms
     *  recorded from the from-date (none: from the beginning of time). */
    @Transactional
    public Person add(String sheetId, String user, String name, Grade grade, Integer terms, LocalDate termsFrom,
            String contact, String tags) {
        sheets.load(sheetId);
        if (people.existsBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                || aliases.existsBySheetIdAndAliasKey(sheetId, Person.keyOf(name))) {
            throw nameInUse();
        }
        if (tree.usedElsewhere(sheetId, name)) {
            throw nameInUseInTree();
        }
        String contactKey = Person.contactOf(contact);
        if (contactKey != null && people.existsBySheetIdAndContact(sheetId, contactKey)) {
            throw contactInUse();
        }
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        Person fresh = new Person(sheetId, name, grade, sheets.today(sheetId));
        if (termsFrom == null) fresh.setTerms(terms);
        else fresh.setTerms(terms, termsFrom);
        fresh.setContact(contactKey);
        List<String> tagged = ClaimService.tagsOf(tags);
        fresh.setTags(tagged);
        useTags(sheetId, tagged);
        Person person = save(fresh);
        Integer termsToday = person.termsOn(sheets.today(sheetId));
        recordChange("PERSON_ADDED", user, person, (termsToday == null ? "" : " terms=" + termsToday)
                + (contactKey == null ? "" : " contact=" + contactKey));
        rollup.recordChanges(user, before);
        return person;
    }

    /** Change a person's name and give them a grade from a from-date (none: the sheet's today), keeping
     *  the grades they were given from other dates; refused when someone has saved the person since
     *  the editor loaded this version of them, or when another person on the sheet has the contact
     *  given (blank or null: none). Refused too when the name, or any of the person's aliases, is used on
     *  another sheet of the sheet's tree, so a person who clashes can only be edited by a rename that
     *  leaves them clashing no more. */
    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, Grade grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        return update(sheetId, user, id, version, name, grade, gradeFrom, terms, contact,
                String.join(",", person.getTags()));
    }

    /** Edit a person as {@link #update(String, String, long, long, String, Grade, LocalDate, Integer, String)},
     *  giving them the tags (comma separated; blank or null: none). */
    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, Grade grade,
            LocalDate gradeFrom, Integer terms, String contact, String tags) {
        return update(sheetId, user, id, version, name, grade, gradeFrom, terms, contact, tags, false);
    }

    /** Edit a person as {@link #update(String, String, long, long, String, Grade, LocalDate, Integer, String, String)};
     *  an explicit grade (a bulk grade) is recorded from its from-date even when already in force then. */
    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, Grade grade,
            LocalDate gradeFrom, Integer terms, String contact, String tags, boolean explicitGrade) {
        return update(sheetId, user, id, version, name, grade, gradeFrom, terms, null, contact, tags, explicitGrade);
    }

    /** Give a person a grade from the from-date (none: the sheet's today) as a bulk grade does: recorded
     *  from it even when already in force then, leaving everything else about them, their terms included,
     *  as it is. */
    @Transactional
    public Person regrade(String sheetId, String user, long id, long version, Grade grade, LocalDate gradeFrom) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (person.getVersion() != version) {
            throw changedMeanwhile();
        }
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        LocalDate today = sheets.today(sheetId);
        person.setGrade(grade, gradeFrom != null ? gradeFrom : today, today, true);
        Person saved = save(person);
        recordChange("PERSON_EDITED", user, saved, "");
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Edit a person as {@link #update(String, String, long, long, String, Grade, LocalDate, Integer, String, String, boolean)},
     *  recording their terms from the terms' from-date, keeping those recorded from other dates (none:
     *  from the beginning of time). */
    @Transactional
    public Person update(String sheetId, String user, long id, long version, String name, Grade grade,
            LocalDate gradeFrom, Integer terms, LocalDate termsFrom, String contact, String tags,
            boolean explicitGrade) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (person.getVersion() != version) {
            throw changedMeanwhile();
        }
        if (people.existsBySheetIdAndNameKeyAndIdNot(sheetId, Person.keyOf(name), id)
                || aliases.existsBySheetIdAndAliasKey(sheetId, Person.keyOf(name))) {
            throw nameInUse();
        }
        Set<String> elsewhere = tree.keysElsewhere(sheetId, id);
        if (elsewhere.contains(Person.keyOf(name))
                || person.getAliases().stream().map(Person::keyOf).anyMatch(elsewhere::contains)) {
            throw nameInUseInTree();
        }
        String contactKey = Person.contactOf(contact);
        if (contactKey != null && people.existsBySheetIdAndContactAndIdNot(sheetId, contactKey, id)) {
            throw contactInUse();
        }
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        boolean contactChanged = !Objects.equals(person.getContact(), contactKey);
        boolean termsChanged = termsFrom != null || !Objects.equals(person.getTerms(), terms)
                || !Objects.equals(person.termsOn(sheets.today(sheetId)), terms);
        person.setName(name);
        // An edit that leaves the grade in force today as it is, with no from-date, records no grade, so it
        // does not hide a grade given later from an earlier date.
        boolean gradeGiven = gradeFrom != null || person.gradeOn(sheets.today(sheetId)) != grade;
        person.setGrade(grade, gradeFrom != null ? gradeFrom : sheets.today(sheetId), sheets.today(sheetId),
                explicitGrade && gradeGiven);
        if (termsFrom == null) person.setTerms(terms);
        else person.setTerms(terms, termsFrom);
        person.setContact(contactKey);
        List<String> tagged = ClaimService.tagsOf(tags);
        person.setTags(tagged);
        useTags(sheetId, tagged);
        Person saved = save(person);
        Integer termsToday = saved.termsOn(sheets.today(sheetId));
        recordChange("PERSON_EDITED", user, saved, (termsChanged ? " terms=" + (termsToday == null ? "" : termsToday) : "")
                + (contactChanged ? " contact=" + (contactKey == null ? "" : contactKey) : ""));
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Give a person a grade from the sheet's today, as the person form would edit them, keeping their
     *  name, terms, contact and tags; refused when someone has saved the person since this version of
     *  them was loaded. */
    @Transactional
    public Person grade(String sheetId, String user, long id, long version, Grade grade) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        return update(sheetId, user, id, version, person.getName(), grade, null, person.getTerms(),
                person.getContact(), String.join(",", person.getTags()));
    }

    /** Give a person another name they are found by; refused when blank, or when it equals, as names
     *  are compared, any name or alias on the sheet or on another sheet of its tree. */
    @Transactional
    public Person addAlias(String sheetId, String user, long id, String alias) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (alias == null || alias.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter an alias.");
        }
        String key = Person.keyOf(alias);
        if (people.existsBySheetIdAndNameKey(sheetId, key) || aliases.existsBySheetIdAndAliasKey(sheetId, key)) {
            throw nameInUse();
        }
        if (tree.usedElsewhere(sheetId, alias)) {
            throw nameInUseInTree();
        }
        person.addAlias(alias);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_ALIAS_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " alias=" + alias.trim());
        return saved;
    }

    /** Retire a person from a date, the sheet's today when none is given. Once the sheet's today reaches
     *  the date, their open claims count in no live total, unless a statement binds them; their settled
     *  claims still count. Claims dated after the date can no longer be added to them. */
    @Transactional
    public Person retire(String sheetId, String user, long id, LocalDate from) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        LocalDate dated = from != null ? from : sheets.today(sheetId);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        person.setRetiredFrom(dated, sheets.today(sheetId));
        Person saved = save(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " from=" + dated);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Unretire a retired person on the sheet's today; refused for anyone not retired. Their retirement is
     *  kept as a past one, from its date to the day unretired, and claims dated within it stay refused. */
    @Transactional
    public Person unretire(String sheetId, String user, long id) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (!person.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a retired person can be unretired.");
        }
        LocalDate today = sheets.today(sheetId);
        Rollup.Snapshot before = rollup.snapshot(sheetId);
        person.unretire(today);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_UNRETIRED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " on=" + today);
        rollup.recordChanges(user, before);
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
        person.hold(dated, to, sheets.today(sheetId));
        Person saved = save(person);
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " from=" + dated);
        rollup.recordChanges(user, before);
        return saved;
    }

    /** Flag a person with a reason until a date (none: the sheet's today), replacing any earlier flag;
     *  refused unless the user is a steward of the sheet, and for a blank reason. While the sheet's
     *  today is on or before the until-date, only a steward may open a claim of the person. */
    @Transactional
    public Person flag(String sheetId, String user, long id, String reason, LocalDate until) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        if (settings.getObject().roleOf(sheetId, user) != Role.STEWARD) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can flag a person.");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the flag a reason.");
        }
        LocalDate dated = until != null ? until : sheets.today(sheetId);
        person.flag(reason, dated);
        Person saved = save(person);
        audit.recordOnCommit("PERSON_FLAGGED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " reason=" + saved.getFlagReason() + " until=" + dated);
        return saved;
    }

    /** Name the user who may approve or reject the person's PENDING claims as a steward may (blank: none). */
    @Transactional
    public Person setApprover(String sheetId, String user, long id, String approver) {
        Person person = people.findByIdAndSheetId(id, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person."));
        person.setApprover(approver);
        Person saved = save(person);
        audit.recordOnCommit("APPROVER_SET sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " user=" + (saved.getApprover() == null ? "" : saved.getApprover()));
        return saved;
    }

    /** Swap the person with the one above them in the list (up) or below them (down); refused for the
     *  top person moving up and the bottom person moving down. */
    @Transactional
    public void move(String sheetId, String user, long id, boolean up) {
        sheets.lock(sheetId);
        List<Person> listed = people.findInListOrder(sheetId);
        int at = -1;
        for (int i = 0; i < listed.size(); i++) if (listed.get(i).getId() == id) at = i;
        if (at < 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person.");
        int to = up ? at - 1 : at + 1;
        if (to < 0 || to >= listed.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    up ? "The top person cannot move up." : "The bottom person cannot move down.");
        }
        Person moving = listed.get(at);
        Person other = listed.get(to);
        long movingPlace = moving.getPosition();
        long otherPlace = other.getPosition();
        String name = moving.getName();
        people.placeAt(moving.getId(), otherPlace);
        people.placeAt(other.getId(), movingPlace);
        audit.recordOnCommit("PERSON_MOVED sheet=" + sheetId + " by=" + user + " name=" + name
                + " position=" + (to + 1));
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
            // Another user took the same name, or the same contact, at the same moment.
            String cause = String.valueOf(raced.getMostSpecificCause().getMessage()).toUpperCase(Locale.ROOT);
            throw cause.contains("PERSON_SHEET_CONTACT") ? contactInUse() : nameInUse();
        }
    }

    private static ResponseStatusException changedMeanwhile() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Someone else has saved this person since it was opened.");
    }

    /** Keep the tags as used on the sheet. */
    private void useTags(String sheetId, List<String> tagged) {
        for (String tag : tagged) {
            if (!sheetTags.existsBySheetIdAndName(sheetId, tag)) sheetTags.save(new SheetTag(sheetId, tag));
        }
    }

    private static ResponseStatusException contactInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That contact is already in use on the sheet.");
    }

    private static ResponseStatusException nameInUseInTree() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on a sheet of this sheet's tree.");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on the sheet.");
    }
}
