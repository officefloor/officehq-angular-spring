package net.officefloor.hq.app.person;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.Audit;
import net.officefloor.hq.app.rollup.Rollups;
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
    private final Rollups rollups;
    private final Audit audit;

    public PersonService(PersonRepository people, SheetService sheets, Rollups rollups, Audit audit) {
        this.people = people;
        this.sheets = sheets;
        this.rollups = rollups;
        this.audit = audit;
    }

    /** The sheet's people in the order they were added. */
    @Transactional(readOnly = true)
    public List<Person> list(String sheetId) {
        return people.findBySheetIdOrderByIdAsc(sheetId);
    }

    /**
     * Add a person to the sheet, with their own terms (null: none) and contact (null or blank: none);
     * refused when the name or the contact is already in use on it.
     */
    @Transactional
    public Person add(String sheetId, String user, String name, String grade, Integer terms, String contact) {
        sheets.load(sheetId);
        requireNameFree(sheetId, name, null);
        requireContactFree(sheetId, contact, null);
        Rollups.Rollup before = rollups.take(sheetId);
        Person person = people.saveAndFlush(new Person(sheetId, name, grade, terms, contact));
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
        Person person = people.findByIdAndSheetId(personId, sheetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such person on this sheet."));
        if (person.getVersion() != version) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "Someone else has saved this person since it was opened.");
        }
        requireNameFree(sheetId, name, person.getId());
        requireContactFree(sheetId, contact, person.getId());
        Rollups.Rollup before = rollups.take(sheetId);
        LocalDate today = sheets.today(sheetId);
        boolean withTerms = termsGiven || !java.util.Objects.equals(person.getTerms(), terms);
        boolean withContact = contactGiven
                || !java.util.Objects.equals(person.getContact(), Person.contactOf(contact));
        person.setName(name);
        person.setTerms(terms);
        person.setContact(contact);
        person.gradeFrom(grade, gradeFrom != null ? gradeFrom : today);
        Person saved = people.saveAndFlush(person);
        audited("PERSON_EDITED", sheetId, user, saved, today, withTerms, withContact);
        rollups.audit(sheetId, user, before);
        return saved;
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
     * Record the change to the person, with the grade in force on the sheet's today and, on an edit or
     * an add that gave them, their own terms and contact (blank when they have none), once it is committed.
     */
    private void audited(String event, String sheetId, String user, Person person, LocalDate today,
            boolean withTerms, boolean withContact) {
        audit.recordOnCommit(event + " sheet=" + sheetId + " by=" + user
                + " name=" + person.getName() + " grade=" + person.gradeOn(today)
                + (withTerms ? " terms=" + (person.getTerms() != null ? person.getTerms() : "") : "")
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

    /** Refuse a name already held on the sheet by anyone other than {@code self}. */
    private void requireNameFree(String sheetId, String name, Long self) {
        people.findBySheetIdAndNameKey(sheetId, Person.keyOf(name))
                .filter(holder -> !holder.getId().equals(self))
                .ifPresent(holder -> {
                    throw nameInUse();
                });
    }

    static ResponseStatusException nameInUse() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "That name is already in use on this sheet.");
    }
}
