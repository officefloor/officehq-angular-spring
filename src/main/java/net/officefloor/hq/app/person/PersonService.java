package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.role.Role;
import net.officefloor.hq.app.role.RoleService;
import net.officefloor.hq.app.rollup.Rollups;
import net.officefloor.hq.app.sheet.SheetRepository;
import net.officefloor.hq.app.sheet.SheetService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** The people kept on a sheet: listed, added and edited. */
@Service
public class PersonService {

    private final PersonRepository people;
    private final SheetService sheets;
    private final SheetRepository sheetStore;
    private final Rollups rollups;
    private final Audit audit;
    private final RoleService roles;

    public PersonService(PersonRepository people, SheetService sheets, SheetRepository sheetStore, Rollups rollups,
            Audit audit, RoleService roles) {
        this.roles = roles;
        this.people = people;
        this.sheets = sheets;
        this.sheetStore = sheetStore;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** The sheet's people in list order. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByPositionAscIdAsc(sheetId);
    }

    /** The ids of the sheet's people whose name or an alias is in use, as a name or alias, on another sheet of its tree. */
    @Transactional(readOnly = true)
    public Set<Long> clashing(String sheetId) {
        Set<String> used = treeKeys(sheetId);
        Set<Long> ids = new HashSet<>();
        for (Person person : people.findBySheetIdOrderByPositionAscIdAsc(sheetId)) {
            if (person.goesByAny(used)) {
                ids.add(person.getId());
            }
        }
        return ids;
    }

    /** Whether the person's name or an alias is in use, as a name or alias, on another sheet of its tree. */
    @Transactional(readOnly = true)
    public boolean clashing(Person person) {
        return person.goesByAny(treeKeys(person.getSheetId()));
    }

    /**
     * Add a person to the sheet, with their own terms (null: none) and contact (null or blank: none);
     * refused when the name or the contact is already in use on it.
     */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact) {
        return add(sheetId, user, name, grade, terms, contact, null);
    }

    /** Add a person as {@link #add(String, String, String, String, Integer, String)} does, with their tags, comma separated (null or blank: none). */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact,
            String tags) {
        sheets.load(sheetId);
        requireNameFree(sheetId, name, null);
        requireContactFree(sheetId, contact, null);
        Rollups.Rollup before = rollups.take(sheetId);
        Person added = new Person(sheetId, name, grade, terms, contact);
        added.tag(Claim.tagsOf(tags));
        added.setPosition(people.findMaxPositionBySheetId(sheetId) + 1);
        Person person = people.saveAndFlush(added);
        audited("PERSON_ADDED", sheetId, user, person, sheets.today(sheetId), terms != null,
                person.getContact() != null);
        rollups.audit(sheetId, user, before);
        return person;
    }

    /**
     * Change a person's name and own terms (null: none) and record their grade from a date (null: the
     * sheet's today), keeping the grades already recorded, from the version of the person the editor loaded; refused when someone
     * has saved the person since, or when the name or contact (null or blank: none) is in use by another
     * person. PERSON_EDITED carries terms=&lt;terms&gt; and contact=&lt;contact&gt; only when the form gave
     * them or the edit changes them.
     */
    @Transactional
    public Person edit(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, String contact, boolean termsGiven, boolean contactGiven) {
        return edit(sheetId, user, personId, version, name, grade, gradeFrom, terms, null, contact, termsGiven,
                contactGiven, null);
    }

    /**
     * Edit a person as {@link #edit} does, recording their own terms from a date (null: from the beginning
     * of time; the terms recorded from other dates are kept), and set their tags from the comma separated
     * text (null: kept as they are). PERSON_EDITED carries the terms in force on the sheet's today.
     */
    @Transactional
    public Person edit(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, LocalDate termsFrom, String contact, boolean termsGiven,
            boolean contactGiven, String tags) {
        return edit(sheetId, user, personId, version, name, grade, gradeFrom, terms, termsFrom, contact, termsGiven,
                contactGiven, tags, false);
    }

    /**
     * Edit a person as {@link #edit} does; a grade chosen on the form (gradeGiven) is recorded from its
     * date even when it is already in force there.
     */
    @Transactional
    public Person edit(String sheetId, String user, long personId, long version, String name, String grade,
            LocalDate gradeFrom, Integer terms, LocalDate termsFrom, String contact, boolean termsGiven,
            boolean contactGiven, String tags, boolean gradeGiven) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (person.getVersion() != version) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "Someone else has saved this person since it was opened.");
        }
        requireNameFree(sheetId, name, person.getId());
        if (person.getAliases().stream().map(Person::keyOf).anyMatch(treeKeys(sheetId)::contains)) {
            throw nameInUse();
        }
        requireContactFree(sheetId, contact, person.getId());
        Rollups.Rollup before = rollups.take(sheetId);
        LocalDate today = sheets.today(sheetId);
        boolean withTerms = termsGiven || (termsFrom == null
                ? !java.util.Objects.equals(person.getTerms(), terms)
                : !person.hasTermsFrom(termsFrom) || !java.util.Objects.equals(person.getTermsFrom().get(termsFrom), terms));
        boolean withContact = contactGiven
                || !java.util.Objects.equals(person.getContact(), Person.contactOf(contact));
        person.setName(name);
        if (termsFrom == null) {
            person.setTerms(terms);
        } else {
            person.termsFrom(terms, termsFrom);
        }
        person.setContact(contact);
        if (tags != null) {
            person.tag(Claim.tagsOf(tags));
        }
        // A grade already in force from the sheet's today is not recorded again, so it does not hold over a
        // grade recorded later from an earlier date, unless it was chosen on the form; one given from another
        // date is recorded from it.
        LocalDate from = gradeFrom != null ? gradeFrom : today;
        boolean dated = (gradeGiven || !from.equals(today)) && !grade.equals(person.getGradesFrom().get(from));
        if (dated || !grade.equals(person.gradeOn(from))) {
            person.gradeFrom(grade, from);
        }
        Person saved = people.saveAndFlush(person);
        audited("PERSON_EDITED", sheetId, user, saved, today, withTerms, withContact);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /** Record a person's grade from the sheet's today, keeping the rest of them, as the person form would. */
    @Transactional
    public Person grade(String sheetId, String user, long personId, long version, String grade) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        return edit(sheetId, user, personId, version, person.getName(), grade, null, person.getTerms(),
                person.getContact(), false, false);
    }

    /**
     * Swap a person with the one above them in the list (up) or below them (down); refused for the top
     * person moving up and the bottom one moving down. PERSON_MOVED carries their new position, counting
     * from 1.
     */
    @Transactional
    public Person move(String sheetId, String user, long personId, boolean up) {
        List<Person> listed = new ArrayList<>(people.findBySheetIdOrderByPositionAscIdAsc(sheetId));
        int at = -1;
        for (int i = 0; i < listed.size(); i++) {
            if (listed.get(i).getId() == personId) {
                at = i;
            }
        }
        if (at < 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet.");
        }
        int to = up ? at - 1 : at + 1;
        if (to < 0 || to >= listed.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    up ? "The top person cannot move up." : "The bottom person cannot move down.");
        }
        java.util.Collections.swap(listed, at, to);
        for (int i = 0; i < listed.size(); i++) {
            Person each = listed.get(i);
            if (each.getPosition() != i + 1) {
                each.setPosition(i + 1);
                people.save(each);
            }
        }
        people.flush();
        Person moved = listed.get(to);
        audit.recordOnCommit("PERSON_MOVED sheet=" + sheetId + " by=" + user
                + " name=" + moved.getName() + " position=" + (to + 1));
        return moved;
    }

    /**
     * Retire a person from a date; with no date, from the sheet's today. Once the sheet's today reaches
     * it, their OPEN and PENDING claims not bound by a statement leave the live totals.
     */
    @Transactional
    public Person retire(String sheetId, String user, long personId, LocalDate from) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        Rollups.Rollup before = rollups.take(sheetId);
        person.retire(from != null ? from : sheets.today(sheetId));
        Person saved = people.saveAndFlush(person);
        audit.recordOnCommit("PERSON_RETIRED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " from=" + saved.getRetiredFrom());
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Unretire a retired person on the sheet's today: they are no longer retired, and the retirement is
     * kept on them, from its date to today. Refused for anyone not retired.
     */
    @Transactional
    public Person unretire(String sheetId, String user, long personId) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (person.getRetiredFrom() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, person.getName() + " is not retired.");
        }
        LocalDate today = sheets.today(sheetId);
        Rollups.Rollup before = rollups.take(sheetId);
        person.unretire(today);
        Person saved = people.saveAndFlush(person);
        audit.recordOnCommit("PERSON_UNRETIRED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " on=" + today);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Put a person on hold from a date (null: the sheet's today) to a date (null: no end); refused
     * when the to-date is before the from-date. Their figures and the sheet's totals move as their
     * OPEN and PENDING claims dated within it, not bound by a statement, leave the live totals.
     */
    @Transactional
    public Person hold(String sheetId, String user, long personId, LocalDate from, LocalDate to) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        LocalDate start = from != null ? from : sheets.today(sheetId);
        if (to != null && to.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A hold cannot end before it starts.");
        }
        Rollups.Rollup before = rollups.take(sheetId);
        person.hold(start, to);
        Person saved = people.saveAndFlush(person);
        audit.recordOnCommit("PERSON_HELD sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " from=" + start);
        rollups.audit(sheetId, user, before);
        return saved;
    }

    /**
     * Flag a person with a reason until a date: while the sheet's today is on or before it, only a
     * steward opens a claim of theirs. Refused unless the user is a steward, and for a blank reason.
     */
    @Transactional
    public Person flag(String sheetId, String user, long personId, String reason, LocalDate until) {
        sheets.lock(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (!Role.STEWARD.equals(roles.roleOf(sheetId, user))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a steward flags a person.");
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A flag needs a reason.");
        }
        person.flag(trimmed, until);
        Person saved = people.saveAndFlush(person);
        audit.recordOnCommit("PERSON_FLAGGED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " reason=" + trimmed + " until=" + until);
        return saved;
    }

    /**
     * Set the user name that may approve or reject the person's PENDING claims as a steward may; a
     * blank name sets none.
     */
    @Transactional
    public Person setApprover(String sheetId, String user, long personId, String approver) {
        sheets.lock(sheetId);
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        String trimmed = approver == null ? "" : approver.trim();
        person.setApprover(trimmed.isEmpty() ? null : trimmed);
        Person saved = people.saveAndFlush(person);
        audit.recordOnCommit("APPROVER_SET sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " user=" + trimmed);
        return saved;
    }

    /**
     * Name another person on the sheet as a patron of this one; refused for the person themselves and
     * for someone already their patron.
     */
    @Transactional
    public Person addPatron(String sheetId, String user, long personId, long patronId) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        Person patron = people.findByIdAndSheetId(patronId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such patron on this sheet."));
        if (person.getId().equals(patron.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A person cannot be their own patron.");
        }
        if (!person.addPatron(patron.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That person is already a patron.");
        }
        Person saved = people.saveAndFlush(person);
        audit.recordOnCommit("PATRON_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " patron=" + patron.getName());
        return saved;
    }

    /**
     * Give a person an alias; refused when it is blank, or when it equals, as names are compared, any
     * name or alias on the sheet.
     */
    @Transactional
    public Person addAlias(String sheetId, String user, long personId, String alias) {
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (alias == null || alias.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An alias is required.");
        }
        if (people.findBySheetIdAndName(sheetId, alias).isPresent()
                || treeKeys(sheetId).contains(Person.keyOf(alias))) {
            throw nameInUse();
        }
        person.addAlias(alias);
        Person saved = people.saveAndFlush(person);
        audit.recordOnCommit("PERSON_ALIAS_ADDED sheet=" + sheetId + " by=" + user
                + " name=" + saved.getName() + " alias=" + Person.clean(alias));
        return saved;
    }

    /**
     * Record the change to the person, with the grade in force on the sheet's today and, on an edit or
     * an add that gave them, their own terms in force on it and contact (blank when they have none), once it is committed.
     */
    private void audited(String event, String sheetId, String user, Person person, LocalDate today,
            boolean withTerms, boolean withContact) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " grade=" + person.gradeOn(today)
                + (withTerms ? " terms=" + (person.termsOn(today) != null ? person.termsOn(today) : "") : "")
                + (withContact ? " contact=" + (person.getContact() != null ? person.getContact() : "") : ""));
    }

    /** Refuse a contact (null or blank: none) already held on the sheet by anyone other than {@code self}. */
    private void requireContactFree(String sheetId, String contact, Long self) {
        String kept = Person.contactOf(contact);
        if (kept == null) {
            return;
        }
        people.findBySheetIdAndContact(sheetId, kept)
                .filter(holder -> !holder.getId().equals(self))
                .ifPresent(holder -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "That contact is already in use on this sheet.");
                });
    }

    /**
     * Refuse a name already held on the sheet, as a name or an alias, by anyone other than {@code self},
     * or held as a name or alias on any other sheet of its tree.
     */
    private void requireNameFree(String sheetId, String name, Long self) {
        if (treeKeys(sheetId).contains(Person.keyOf(name))) {
            throw nameInUse();
        }
        people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                .filter(holder -> !holder.getId().equals(self))
                .ifPresent(holder -> {
                    throw nameInUse();
                });
        people.findBySheetIdAndAliasKey(sheetId, Person.keyOf(name))
                .filter(holder -> !holder.getId().equals(self))
                .ifPresent(holder -> {
                    throw nameInUse();
                });
    }

    /**
     * The keys of the names and aliases in use on the other sheets of the sheet's tree: its ancestors,
     * its descendants and theirs, among the sheets that exist.
     */
    private Set<String> treeKeys(String sheetId) {
        List<String> tree = new ArrayList<>(sheetStore.ancestors(sheetId));
        tree.addAll(sheetStore.descendants(sheetId));
        tree.remove(sheetId);
        if (tree.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>(people.findNameKeysBySheetIdIn(tree));
        keys.addAll(people.findAliasKeysBySheetIdIn(tree));
        return keys;
    }

    static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet.");
    }
}
