package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.ClaimService;
import net.officefloor.hq.app.grade.GradeService;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.role.SheetRole;
import net.officefloor.hq.app.rollup.Rollup;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The people kept on a sheet. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;
    private final Rollup rollup;
    private final Audit audit;
    private final RoleService roles;
    private final ClaimService claims;
    private final GradeService grades;

    public PersonService(PersonRepository people, SheetService sheets, Rollup rollup, Audit audit,
            RoleService roles, ClaimService claims, GradeService grades) {
        this.grades = grades;
        this.claims = claims;
        this.people = people;
        this.sheets = sheets;
        this.rollup = rollup;
        this.audit = audit;
        this.roles = roles;
    }

    /** The people on the sheet in list order. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
    }

    /** Add a person to the sheet. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms) {
        return add(sheetId, user, name, grade, terms, null);
    }

    /** Add a person to the sheet with a contact (null: none); refused when another person on the
     *  sheet holds it. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact) {
        return add(sheetId, user, name, grade, terms, contact, null);
    }

    /** Add a person to the sheet with a contact (null: none) and tags (as {@link net.officefloor.hq.app.claim.Tags}
     *  stores them; null: none); refused when another person on the sheet holds the contact. */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact,
            String tags) {
        sheets.load(sheetId, user);
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, name) || people.existsAlias(sheetId, name)
                || inTree(treeNames(sheetId), name)) {
            throw nameInUse();
        }
        if (contact != null && people.existsBySheetIdAndContact(sheetId, contact)) {
            throw contactInUse();
        }
        grades.checkChoosable(sheetId, grade);
        return rollup.tracking(sheetId, user, () -> {
            Person fresh = new Person(sheetId, name, grade, people.nextPosition(sheetId));
            fresh.setTerms(terms);
            fresh.setContact(contact);
            fresh.setTags(tags);
            Person person = saveUnique(fresh);
            claims.keepTags(sheetId, tags);
            audit.recordOnCommit(record("PERSON_ADDED", sheetId, user, person, sheets.today(sheetId))
                    + (terms != null ? " terms=" + terms : "")
                    + (contact != null ? " contact=" + contact : ""));
            return person;
        });
    }

    /** Change a person's name, and record their grade from a day (null: the sheet's today), keeping
     *  the grades already recorded; a grade left as the one in force on the sheet's today, with no
     *  day given, records nothing; and set their own terms (null: none) and contact (null: none),
     *  refused when another person on the sheet holds that contact. Also refused when the person is no longer at the version the editor
     *  loaded: another user saved them in the meantime. */
    @Transactional
    public Person edit(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        return edit(sheetId, user, personId, version, name, grade, gradeFrom, terms, contact, person.getTags());
    }

    /** Edit a person as above, also setting their tags (as {@link net.officefloor.hq.app.claim.Tags} stores
     *  them; null: none). */
    @Transactional
    public Person edit(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact, String tags) {
        return edit(sheetId, user, personId, version, name, grade, gradeFrom, terms, null, contact, tags);
    }

    /** Edit a person as above, recording their terms from a day (null: from the beginning of time),
     *  keeping those recorded for other days. */
    @Transactional
    public Person edit(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, LocalDate termsFrom, String contact, String tags) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (person.getVersion() != version) {
            throw changedMeanwhile();
        }
        if (people.existsBySheetIdAndNameIgnoreCaseAndIdNot(sheetId, name, personId)
                || people.existsAlias(sheetId, name)) {
            throw nameInUse();
        }
        Set<String> tree = treeNames(sheetId);
        if (inTree(tree, name) || person.getAliases().stream().anyMatch(a -> inTree(tree, a))) {
            throw nameInUse();
        }
        if (contact != null && people.existsBySheetIdAndContactAndIdNot(sheetId, contact, personId)) {
            throw contactInUse();
        }
        return save(sheetId, user, person, name, grade, gradeFrom, terms, termsFrom, contact, tags);
    }

    /** Give each of the people a grade from a day (null: the sheet's today), in list order, exactly as the person form would: each keeps their name, terms and contact and
     *  is followed by their own PERSON_EDITED and rollup lines. Refused as a whole, changing no one,
     *  when an id names no person on the sheet. A viewer has each of them refused: no one changes. */
    @Transactional
    public List<Person> grade(String sheetId, String user, Set<Long> personIds, String grade, LocalDate gradeFrom) {
        if (SheetRole.VIEWER.equals(roles.roleOf(sheetId, user))) {
            return List.of();
        }
        List<Person> chosen = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> personIds.contains(p.getId())).toList();
        if (chosen.size() != personIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet");
        }
        return chosen.stream()
                .map(p -> save(sheetId, user, p, p.getName(), grade, gradeFrom, p.getTerms(), null, p.getContact(),
                        p.getTags()))
                .toList();
    }

    /** Merge a grade into another: every person whose grade in force on the sheet's today is the
     *  merged grade is given the other from the sheet's today, in list order, exactly as the person
     *  form would. Refused unless the user is a steward (403), and when the grades are the same or
     *  either is no grade (400). Answers the people it changed. */
    @Transactional
    public List<Person> mergeGrade(String sheetId, String user, String from, String into) {
        if (!grades.isGrade(sheetId, from) || !grades.isGrade(sheetId, into)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such grade on the sheet");
        }
        if (from.equals(into)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A grade cannot be merged into itself");
        }
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can merge a grade");
        }
        sheets.load(sheetId, user);
        LocalDate today = sheets.today(sheetId);
        List<Person> chosen = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> from.equals(p.gradeOn(today))).toList();
        audit.recordOnCommit("GRADE_MERGED sheet=" + sheetId + " by=" + user + " from=" + from + " into=" + into);
        // The merge as a whole is a change too: after each person's own lines, the figures it moved.
        return rollup.tracking(sheetId, user, () -> chosen.stream()
                .map(p -> save(sheetId, user, p, p.getName(), into, null, p.getTerms(), null, p.getContact(),
                        p.getTags()))
                .toList());
    }

    /** Save the person's name, grade from a day, terms from a day, contact and tags as an edit does,
     *  recording it; the terms recorded, when they change, as those in force on the sheet's today. */
    private Person save(String sheetId, String user, Person person, String name, String grade, LocalDate gradeFrom,
            Integer terms, LocalDate termsFrom, String contact, String tags) {
        if (choosesGrade(sheetId, person, grade, gradeFrom)) {
            grades.checkChoosable(sheetId, grade);
        }
        return rollup.tracking(sheetId, user, () -> {
            LocalDate today = sheets.today(sheetId);
            Integer undatedBefore = person.getTerms();
            List<TermsFrom> datedBefore = person.getDatedTerms();
            String contactBefore = person.getContact();
            person.setName(name);
            person.setTermsFrom(terms, termsFrom);
            boolean termsChanged = !Objects.equals(undatedBefore, person.getTerms())
                    || !sameTerms(datedBefore, person.getDatedTerms());
            Integer termsNow = person.termsOn(today);
            person.setContact(contact);
            person.setTags(tags);
            claims.keepTags(sheetId, tags);
            if (choosesGrade(sheetId, person, grade, gradeFrom)) {
                person.setGradeFrom(grade, gradeFrom != null ? gradeFrom : today);
            }
            Person saved = saveUnique(person);
            audit.recordOnCommit(record("PERSON_EDITED", sheetId, user, saved, today)
                    + (termsChanged ? " terms=" + (termsNow != null ? termsNow : "") : "")
                    + (Objects.equals(contactBefore, contact) ? "" : " contact=" + (contact != null ? contact : "")));
            return saved;
        });
    }

    /** Whether saving the grade from a day (null: the sheet's today) records it for the person, so
     *  is a choice of it: a grade left as the one in force on the sheet's today, with no day given,
     *  is not. */
    private boolean choosesGrade(String sheetId, Person person, String grade, LocalDate gradeFrom) {
        return gradeFrom != null || !grade.equals(person.gradeOn(sheets.today(sheetId)));
    }

    private static boolean sameTerms(List<TermsFrom> a, List<TermsFrom> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).getFromDate().equals(b.get(i).getFromDate())
                    || !Objects.equals(a.get(i).getTerms(), b.get(i).getTerms())) {
                return false;
            }
        }
        return true;
    }

    /** Give a person an alias, keeping their others; refused when it equals, as names are compared,
     *  any name or alias on the sheet. */
    @Transactional
    public Person addAlias(String sheetId, String user, long personId, String alias) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (people.existsBySheetIdAndNameIgnoreCase(sheetId, alias) || people.existsAlias(sheetId, alias)
                || inTree(treeNames(sheetId), alias)) {
            throw nameInUse();
        }
        // Tracked so that the sheet's history holds the alias from the day it was given.
        Person saved = rollup.tracking(sheetId, user, () -> {
            person.addAlias(alias);
            return saveUnique(person);
        });
        audit.recordOnCommit("PERSON_ALIAS_ADDED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " alias=" + alias);
        return saved;
    }

    /** Whether the person clashes: their name or an alias is used as a name or alias on another
     *  sheet of their sheet's tree (see {@link SheetService#tree}). */
    @Transactional(readOnly = true)
    public boolean clashing(String sheetId, Person person) {
        return clashing(sheetId).test(person);
    }

    /** A test of whether a person on the sheet clashes, reading the sheet's tree once. */
    @Transactional(readOnly = true)
    public Predicate<Person> clashing(String sheetId) {
        Set<String> tree = treeNames(sheetId);
        if (tree.isEmpty()) {
            return p -> false;
        }
        return p -> inTree(tree, p.getName()) || p.getAliases().stream().anyMatch(a -> inTree(tree, a));
    }

    /** The names and aliases used on the other sheets of the sheet's tree, lower-cased. */
    private Set<String> treeNames(String sheetId) {
        Set<String> tree = sheets.tree(sheetId);
        if (tree.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new HashSet<>(people.namesOn(tree));
        names.addAll(people.aliasesOn(tree));
        return names;
    }

    private static boolean inTree(Set<String> treeNames, String name) {
        return treeNames.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Swap a person with the one above them in the list (up) or below them (down), recording their
     *  new position counting from 1; refused when they are at the top and moving up, or at the
     *  bottom and moving down. Answers the people in their new list order. */
    @Transactional
    public List<Person> move(String sheetId, String user, long personId, boolean up) {
        List<Person> listed = new ArrayList<>(people.findBySheetIdOrderByPositionAscIdAsc(sheetId));
        int at = -1;
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).getId() == personId) {
                at = i;
            }
        }
        if (at < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet");
        }
        int to = up ? at - 1 : at + 1;
        if (to < 0 || to >= listed.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    up ? "The person at the top cannot move up" : "The person at the bottom cannot move down");
        }
        Person moved = listed.get(at);
        Collections.swap(listed, at, to);
        // Number the whole list afresh from 1, so people who shared a position are told apart.
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).getPosition() != i + 1) {
                people.placeAt(listed.get(i).getId(), i + 1);
            }
        }
        audit.recordOnCommit("PERSON_MOVED sheet=" + sheetId + " by=" + user + " name=" + moved.getName()
                + " position=" + (to + 1));
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
    }

    /** Retire each of the people from a day (null: the sheet's today), in list order, exactly as a
     *  row's retire would: each is followed by their own PERSON_RETIRED and rollup lines. Refused as
     *  a whole, changing no one, when an id names no person on the sheet. A viewer has each of them
     *  refused: no one changes. */
    @Transactional
    public List<Person> retire(String sheetId, String user, Set<Long> personIds, LocalDate from) {
        if (SheetRole.VIEWER.equals(roles.roleOf(sheetId, user))) {
            return List.of();
        }
        List<Person> chosen = people.findBySheetIdOrderByPositionAscIdAsc(sheetId).stream()
                .filter(p -> personIds.contains(p.getId())).toList();
        if (chosen.size() != personIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet");
        }
        return chosen.stream().map(p -> retire(sheetId, user, p, from)).toList();
    }

    /** Retire a person from a day; a day left out takes the sheet's today. */
    @Transactional
    public Person retire(String sheetId, String user, long personId, LocalDate from) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        return retire(sheetId, user, person, from);
    }

    /** Retire the person from a day (null: the sheet's today), recording it. */
    private Person retire(String sheetId, String user, Person person, LocalDate from) {
        LocalDate on = from != null ? from : sheets.today(sheetId);
        return rollup.tracking(sheetId, user, () -> {
            person.setRetiredFrom(on);
            Person saved = saveUnique(person);
            audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                    + " from=" + on);
            return saved;
        });
    }

    /** Unretire a person on the sheet's today: their retirement is kept as a past one, to that day.
     *  Refused when they are not retired. */
    @Transactional
    public Person unretire(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (!person.isRetired()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a retired person can be unretired");
        }
        LocalDate on = sheets.today(sheetId);
        return rollup.tracking(sheetId, user, () -> {
            person.unretire(on);
            Person saved = saveUnique(person);
            audit.recordOnCommit("PERSON_UNRETIRED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                    + " on=" + on);
            return saved;
        });
    }

    /** Put a person on hold from a day (null: the sheet's today) to a day (null: open-ended),
     *  keeping their other holds; refused when the to-date is before the from-date. */
    @Transactional
    public Person hold(String sheetId, String user, long personId, LocalDate from, LocalDate to) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        LocalDate on = from != null ? from : sheets.today(sheetId);
        if (to != null && to.isBefore(on)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A hold cannot end before it starts");
        }
        return rollup.tracking(sheetId, user, () -> {
            person.addHold(on, to);
            Person saved = saveUnique(person);
            audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                    + " from=" + on);
            return saved;
        });
    }

    /** Flag a person with a reason (kept trimmed) until a day (included; null: the sheet's
     *  today), replacing any flag they had. Refused unless the user is a steward (403) and the reason is not blank (400). */
    @Transactional
    public Person flag(String sheetId, String user, long personId, String reason, LocalDate until) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        if (!SheetRole.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward can flag a person");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give a reason for the flag");
        }
        String why = reason.trim();
        LocalDate last = until != null ? until : sheets.today(sheetId);
        person.flag(why, last);
        Person saved = saveUnique(person);
        audit.recordOnCommit("PERSON_FLAGGED sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " reason=" + why + " until=" + last);
        return saved;
    }

    /** Set the person's approver: the user (kept trimmed; blank: none) who may approve or reject
     *  their pending claims as a steward may. */
    @Transactional
    public Person setApprover(String sheetId, String user, long personId, String approver) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet"));
        String who = approver == null || approver.isBlank() ? null : approver.trim();
        person.setApprover(who);
        Person saved = saveUnique(person);
        audit.recordOnCommit("APPROVER_SET sheet=" + sheetId + " by=" + user + " name=" + saved.getName()
                + " user=" + (who == null ? "" : who));
        return saved;
    }

    /** A record of the person with the grade in force on the sheet's today. */
    private static String record(String event, String sheetId, String user, Person person, LocalDate today) {
        return event + " sheet=" + sheetId + " by=" + user + " name=" + person.getName() + " grade="
                + person.gradeOn(today);
    }

    /** Save, refusing a name or contact another user took on the sheet in the meantime, or a person
     *  another user saved in the meantime. */
    private Person saveUnique(Person person) {
        try {
            return people.saveAndFlush(person);
        } catch (DataIntegrityViolationException e) {
            String constraint = String.valueOf(e.getMostSpecificCause().getMessage()).toUpperCase(Locale.ROOT);
            throw constraint.contains("PERSON_SHEET_CONTACT_UNIQUE") ? contactInUse() : nameInUse();
        } catch (OptimisticLockingFailureException e) {
            throw changedMeanwhile();
        }
    }

    private static ResponseStatusException changedMeanwhile() {
        return new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Someone else saved this person in the meantime");
    }

    private static ResponseStatusException contactInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That contact is already held on this sheet");
    }

    private static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet");
    }
}
