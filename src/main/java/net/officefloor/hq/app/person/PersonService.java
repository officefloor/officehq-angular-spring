package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.SheetTag;
import net.officefloor.hq.app.claim.SheetTagRepository;
import net.officefloor.hq.app.grade.GradeService;
import net.officefloor.hq.app.rollup.RollupService;
import net.officefloor.hq.app.settings.Role;
import net.officefloor.hq.app.settings.SettingsService;
import net.officefloor.hq.app.sheet.Sheet;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The people kept on each sheet. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetRepository sheets;
    private final Audit audit;
    private final RollupService rollup;
    private final SheetService sheetService;
    private final PersonPatronRepository patrons;
    private final PersonWatchRepository watches;
    private final PersonTree tree;
    private final SheetTagRepository tagRepository;
    private final PersonSnapshotRepository snapshots;
    private final SettingsService settings;
    private final GradeService grades;

    public PersonService(PersonRepository people, SheetRepository sheets, Audit audit, RollupService rollup,
            SheetService sheetService, PersonPatronRepository patrons, PersonWatchRepository watches, PersonTree tree,
            SheetTagRepository tagRepository, PersonSnapshotRepository snapshots, SettingsService settings,
            GradeService grades) {
        this.people = people;
        this.grades = grades;
        this.settings = settings;
        this.snapshots = snapshots;
        this.tagRepository = tagRepository;
        this.tree = tree;
        this.patrons = patrons;
        this.watches = watches;
        this.sheets = sheets;
        this.audit = audit;
        this.rollup = rollup;
        this.sheetService = sheetService;
    }

    /** The sheet's people in list order. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
    }

    @Transactional(readOnly = true)
    public Person get(String sheetId, long personId) {
        return people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
    }

    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms) {
        return add(sheetId, user, name, grade, terms, null);
    }

    /** Adds a person with their own terms (none when null) and contact (none when blank); refused when
     * another person on the sheet has the name or the contact, or another sheet of its tree uses the name. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact) {
        return add(sheetId, user, name, grade, terms, contact, null);
    }

    /** Adds a person as {@link #add(String, String, String, String, Integer, String)} does, with the tags
     * {@code tags} (see {@link Claim#tagsOf}; none when null). */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact,
            String tags) {
        if (!sheets.existsById(sheetId)) {
            throw new SheetNotFoundException();
        }
        grades.requireOpen(sheetId, grade);
        requireFree(sheetId, name, null);
        tree.requireFree(sheetId, name);
        requireContactFree(sheetId, contact, null);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        Person created = new Person(sheetId, name, grade);
        created.setTerms(terms);
        created.setContact(contact);
        created.setTags(tags);
        created.setPosition(people.maxPosition(sheetId) + 1);
        Person person = people.saveAndFlush(created);
        keepTags(person);
        List<String> fields = new ArrayList<>(List.of("name=" + person.getName(), "grade=" + grade));
        if (terms != null) fields.add("terms=" + terms);
        if (person.getContact() != null) fields.add("contact=" + person.getContact());
        audit.change("PERSON_ADDED", sheetId, user, fields.toArray(String[]::new));
        rollup.recordSince(sheetId, user, before);
        return person;
    }

    /** The sheet's today; null when the sheet has never been loaded. */
    @Transactional(readOnly = true)
    public LocalDate today(String sheetId) {
        return sheets.findById(sheetId).map(Sheet::getToday).orElse(null);
    }

    /** Saves the person as edited from {@code version}, giving them {@code grade} from {@code gradeFrom}
     * (the sheet's today when left out, in place of one given from the same date) and keeping
     * the grades given from other dates; refused when they
     * have been saved since. The person's own terms become {@code terms} (none when null), and their
     * contact {@code contact} (none when blank), refused when another person on the sheet has it. Refused
     * too while the new name, or any alias of theirs, is used on another sheet of the sheet's tree. */
    @Transactional
    public Person update(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        return update(sheetId, user, personId, version, name, grade, gradeFrom, terms, contact,
                person.getTags().isEmpty() ? null : String.join(",", person.getTags()));
    }

    /** Saves the person as {@link #update(String, String, long, long, String, String, LocalDate, Integer,
     * String)} does, giving them the tags {@code tags} (see {@link Claim#tagsOf}; none when null). */
    @Transactional
    public Person update(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact, String tags) {
        return update(sheetId, user, personId, version, name, grade, gradeFrom, terms, null, contact, tags);
    }

    /** Saves the person as {@link #update(String, String, long, long, String, String, LocalDate, Integer,
     * String, String)} does, but with {@code terms} (none when null) given from {@code termsFrom}, in place
     * of those given from the same date and keeping those given from other dates; from the beginning of
     * time when {@code termsFrom} is null. */
    @Transactional
    public Person update(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, LocalDate termsFrom, String contact, String tags) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getVersion() != version) {
            throw new PersonStaleException(person);
        }
        LocalDate today = sheetService.today(sheetId);
        // A retired grade stays with whoever has it today, but no edit gives it anew.
        if (gradeFrom != null || !Objects.equals(grade, person.gradeOn(today))) grades.requireOpen(sheetId, grade);
        requireFree(sheetId, name, person.getId());
        tree.requireFree(sheetId, name);
        person.getAliases().forEach(alias -> tree.requireFree(sheetId, alias));
        requireContactFree(sheetId, contact, person.getId());
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.setName(name);
        // A grade left as it is today, with no from-date, adds no dated grade.
        if (gradeFrom != null || !Objects.equals(grade, person.gradeOn(today))) {
            person.setGrade(grade, gradeFrom != null ? gradeFrom : today);
        }
        // The terms are recorded when the save moves the terms shown, those in force today, changes
        // the undated terms to other than those shown before it, or gives other terms from a date.
        Integer termsBefore = person.termsOn(today);
        boolean undatedChanged = termsFrom == null && !Objects.equals(person.getTerms(), terms)
                && !Objects.equals(termsBefore, terms);
        boolean datedChanged = termsFrom != null && person.getDatedTerms().stream()
                .noneMatch(t -> termsFrom.equals(t.getFrom()) && Objects.equals(t.getTerms(), terms));
        if (termsFrom == null) {
            person.setTerms(terms);
        } else {
            person.setTerms(terms, termsFrom);
        }
        boolean termsChanged = undatedChanged || datedChanged || !Objects.equals(termsBefore, person.termsOn(today));
        boolean contactChanged = !Objects.equals(person.getContact(), Person.contactOf(contact));
        person.setContact(contact);
        person.setTags(tags);
        people.flush();
        keepTags(person);
        List<String> fields = new ArrayList<>(List.of("name=" + person.getName(), "grade=" + person.gradeOn(today)));
        Integer termsToday = person.termsOn(today);
        if (termsChanged) fields.add("terms=" + (termsToday == null ? "" : termsToday));
        if (contactChanged) fields.add("contact=" + (person.getContact() == null ? "" : person.getContact()));
        audit.change("PERSON_EDITED", sheetId, user, fields.toArray(String[]::new));
        rollup.recordSince(sheetId, user, before);
        return person;
    }

    /** Gives the person {@code grade} from the sheet's today, saved from {@code version} exactly as the
     * person form saves it, keeping their name, terms, contact and tags. */
    @Transactional
    public Person grade(String sheetId, String user, long personId, long version, String grade) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        return update(sheetId, user, personId, version, person.getName(), grade, null, person.getTerms(),
                person.getContact());
    }

    /** Swaps the person with the one above them in the list ({@code up}) or below; refused for the
     * top person moving up and the bottom one moving down. */
    @Transactional
    public Person move(String sheetId, String user, long personId, boolean up) {
        List<Person> listed = people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
        int at = -1;
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).getId() == personId) at = i;
        }
        if (at < 0) throw new PersonNotFoundException();
        int to = up ? at - 1 : at + 1;
        if (to < 0 || to >= listed.size()) throw new PersonMoveException();
        Person person = listed.get(at);
        Person other = listed.get(to);
        Long position = person.getPosition();
        person.setPosition(other.getPosition());
        other.setPosition(position);
        people.flush();
        audit.change("PERSON_MOVED", sheetId, user, "name=" + person.getName(), "position=" + (to + 1));
        return person;
    }

    /** Retires the person from {@code from} (the sheet's today when left out); the figures it moves are
     * recorded as {@code user}'s change. */
    @Transactional
    public Person retire(String sheetId, String user, long personId, LocalDate from) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate dated = from != null ? from : sheetService.today(sheetId);
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.setRetiredFrom(dated);
        people.flush();
        audit.change("PERSON_RETIRED", sheetId, user, "name=" + person.getName(), "from=" + dated);
        rollup.recordSince(sheetId, user, before);
        return person;
    }

    /** Unretires the person on the sheet's today, keeping the retirement as a past one; refused for one
     * not retired. The figures it moves are recorded as {@code user}'s change. */
    @Transactional
    public Person unretire(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate today = sheetService.today(sheetId);
        if (!person.isRetired() || today == null) {
            throw new PersonUnretireException();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.unretire(today);
        people.flush();
        audit.change("PERSON_UNRETIRED", sheetId, user, "name=" + person.getName(), "on=" + today);
        rollup.recordSince(sheetId, user, before);
        return person;
    }

    /** Flags the person with {@code reason} (kept without the spaces around it) until {@code until},
     * in place of any flag they had: while the sheet's today is on or before it, only a steward may open
     * a claim of theirs. Refused unless the user is a steward, and when the reason is blank or the until
     * date is left out. */
    @Transactional
    public Person flag(String sheetId, String user, long personId, String reason, LocalDate until) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (settings.roleOf(sheetId, user) != Role.STEWARD || reason == null || reason.isBlank() || until == null) {
            throw new PersonFlagException();
        }
        person.flag(reason.trim(), until);
        people.flush();
        audit.change("PERSON_FLAGGED", sheetId, user, "name=" + person.getName(), "reason=" + reason.trim(),
                "until=" + until);
        return person;
    }

    /** Makes {@code approver} (kept without the spaces around it; none when blank) the person's
     * approver: that user may approve or reject their PENDING claims as a steward may. */
    @Transactional
    public Person setApprover(String sheetId, String user, long personId, String approver) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        String name = approver == null || approver.isBlank() ? null : approver.trim();
        person.setApprover(name);
        people.flush();
        audit.change("APPROVER_SET", sheetId, user, "name=" + person.getName(), "user=" + (name == null ? "" : name));
        return person;
    }

    /** Puts the person on a hold from {@code from} (the sheet's today when left out) to {@code to}
     * (no end when left out); refused when it would end before it starts. The same hold may be put on
     * again. A hold ends on its to-date: there is no release. */
    @Transactional
    public Person hold(String sheetId, String user, long personId, LocalDate from, LocalDate to) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        LocalDate dated = from != null ? from : sheetService.today(sheetId);
        if (dated == null || (to != null && to.isBefore(dated))) {
            throw new PersonHoldException();
        }
        RollupService.Snapshot before = rollup.snapshot(sheetId);
        person.addHold(new PersonHold(dated, to));
        people.flush();
        audit.change("PERSON_HELD", sheetId, user, "name=" + person.getName(), "from=" + dated);
        rollup.recordSince(sheetId, user, before);
        return person;
    }

    /** The sheet's snapshots in the order they were written; as of {@code asOf}, only those of the days
     * on or before it, the day it stood at the end of (all of them when null). */
    @Transactional(readOnly = true)
    public List<PersonSnapshot> snapshots(String sheetId, LocalDate asOf) {
        return asOf == null ? snapshots.findBySheetIdOrderByIdAsc(sheetId)
                : snapshots.findBySheetIdAndDayLessThanEqualOrderByIdAsc(sheetId, asOf);
    }

    /** The sheet's patrons in the order they were named. */
    @Transactional(readOnly = true)
    public List<PersonPatron> patrons(String sheetId) {
        return patrons.findBySheetIdOrderByIdAsc(sheetId);
    }

    /** Names another person on the sheet as the person's patron; refused for the person themselves
     * and for one they have already named. */
    @Transactional
    public PersonPatron addPatron(String sheetId, String user, long personId, long patronId) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        Person patron = people.findByIdAndSheetId(patronId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (person.getId().equals(patron.getId()) || patrons.existsByPersonIdAndPatronId(person.getId(), patron.getId())) {
            throw new PersonPatronException();
        }
        PersonPatron added = patrons.saveAndFlush(new PersonPatron(sheetId, person.getId(), patron.getId()));
        audit.change("PATRON_ADDED", sheetId, user, "name=" + person.getName(), "patron=" + patron.getName());
        return added;
    }

    /** The ids of the people the user watches on the sheet, in the order they started watching. */
    @Transactional(readOnly = true)
    public List<Long> watched(String sheetId, String user) {
        return watches.findBySheetIdAndUserOrderByIdAsc(sheetId, user).stream().map(PersonWatch::getPersonId).toList();
    }

    /** Starts (on) or stops (off) the user watching the person; refused when they already do, or
     * do not. */
    @Transactional
    public Person watch(String sheetId, String user, long personId, boolean on) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        var current = watches.findByPersonIdAndUser(person.getId(), user);
        if (on == current.isPresent()) throw new PersonWatchException();
        if (on) {
            watches.saveAndFlush(new PersonWatch(sheetId, person.getId(), user));
        } else {
            watches.delete(current.get());
            watches.flush();
        }
        audit.change(on ? "PERSON_WATCHED" : "PERSON_UNWATCHED", sheetId, user, "name=" + person.getName());
        return person;
    }

    /** Gives the person the alias {@code alias}; refused when it is blank, or equals (as names are
     * compared) any name or alias on the sheet or on another sheet of its tree. */
    @Transactional
    public Person addAlias(String sheetId, String user, long personId, String alias) {
        Person person = people.findByIdAndSheetId(personId, sheetId).orElseThrow(PersonNotFoundException::new);
        if (alias == null || alias.isBlank()) throw new PersonAliasException();
        String key = Person.keyOf(alias);
        if (people.findBySheetIdAndNameKey(sheetId, key).isPresent()
                || people.findBySheetIdAndAliasKey(sheetId, key).isPresent() || tree.taken(sheetId, alias)) {
            throw new PersonAliasException();
        }
        person.addAlias(alias);
        people.flush();
        audit.change("PERSON_ALIAS_ADDED", sheetId, user, "name=" + person.getName(), "alias=" + alias.trim());
        return person;
    }

    /** Whether the person's name or an alias of theirs is used on another sheet of their tree. */
    @Transactional(readOnly = true)
    public boolean clashes(Person person) {
        return tree.clashes(person);
    }

    /** Refuses a name another person on the sheet (other than {@code self}) already has, and a name
     * any alias on the sheet has. */
    private void requireFree(String sheetId, String name, Long self) {
        people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> {
                    throw new PersonNameTakenException();
                });
        if (people.findBySheetIdAndAliasKey(sheetId, Person.keyOf(name)).isPresent()) {
            throw new PersonNameTakenException();
        }
    }

    /** Keep each of the person's tags among those ever used on its sheet. */
    private void keepTags(Person person) {
        for (String tag : person.getTags()) {
            SheetTag.Key key = new SheetTag.Key(person.getSheetId(), tag);
            if (!tagRepository.existsById(key)) tagRepository.save(new SheetTag(person.getSheetId(), tag));
        }
    }

    /** Refuses a contact another person on the sheet (other than {@code self}) already has; a blank
     * contact is none, and is never refused. */
    private void requireContactFree(String sheetId, String contact, Long self) {
        String kept = Person.contactOf(contact);
        if (kept == null) return;
        people.findBySheetIdAndContact(sheetId, kept)
                .filter(other -> !other.getId().equals(self))
                .ifPresent(other -> {
                    throw new PersonContactTakenException();
                });
    }
}
