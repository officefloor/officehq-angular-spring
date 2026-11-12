package net.officefloor.hq.app.person;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;

/** A person on a sheet. */
@Entity
@Table(name = "person")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    /** The name as compared for uniqueness on the sheet: trimmed and lower-cased. */
    @Column(name = "name_key", nullable = false)
    private String nameKey;

    /** The person's grades, each from its from-date; the first one given has none. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private List<PersonGrade> grades = new ArrayList<>();

    /** The person's own terms in whole days, ahead of their grade's and the sheet's; null when they have none. */
    @Column(name = "terms")
    private Integer terms;

    /** The person's own terms dated from a from-date, each in force until the next later-dated one. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private List<PersonTerms> datedTerms = new ArrayList<>();

    /** The person's contact, trimmed and lower-cased; null when they have none. */
    @Column(name = "contact")
    private String contact;

    /** The person's tags: trimmed, lower-cased, without repeats, sorted and joined by commas; null when they have none. */
    @Column(name = "tags")
    private String tags;

    /** The date the person is retired from; null while not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** The sheet's today when the person was added; null for a person added before that was kept. */
    @Column(name = "added_on")
    private LocalDate addedOn;

    /** The sheet's today when the person's retirement was recorded; null when not retired or recorded before that was kept. */
    @Column(name = "retired_on")
    private LocalDate retiredOn;

    /** The person's holds: their OPEN claims dated within one, not bound by a statement, count in no live total. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private List<PersonHold> holds = new ArrayList<>();

    /** The person's past retirements, each ended by an unretire. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private List<PersonRetirement> retirements = new ArrayList<>();

    /** The sheet the person was transferred from; null when they were not transferred here. */
    @Column(name = "origin_sheet")
    private String originSheet;

    /** The sheet's today when the person arrived here by a transfer; null when they did not arrive by one. */
    @Column(name = "transferred_on")
    private LocalDate transferredOn;

    /** Where the person stands in the list: listed by it, then by id. */
    @Column(name = "position", nullable = false)
    private long position;

    /** Raised by one on each save; a save of an older version is refused. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        setName(name);
        grades.add(new PersonGrade(grade, null));
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getName() {
        return name;
    }

    /** Keeps the name with the spaces before and after it removed. */
    public void setName(String name) {
        this.name = name.trim();
        this.nameKey = key(name);
    }

    /** The key a name is compared on: two names with the same key are the same name. */
    public static String key(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /** The person's grades, the undated one first and then by from-date. */
    public List<PersonGrade> getGrades() {
        return grades.stream()
                .sorted(Comparator.comparing(PersonGrade::getFromDate,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** The grade in force on the date: the one with the latest from-date on or before it. */
    public String gradeOn(LocalDate date) {
        String grade = null;
        for (PersonGrade g : getGrades()) {
            if (g.getFromDate() == null || !g.getFromDate().isAfter(date)) {
                grade = g.getGrade();
            }
        }
        return grade;
    }

    /** Record the grade from the date, replacing the grade the person had from that same date. */
    public void setGrade(String grade, LocalDate fromDate) {
        for (PersonGrade g : grades) {
            if (Objects.equals(g.getFromDate(), fromDate)) {
                g.setGrade(grade);
                return;
            }
        }
        grades.add(new PersonGrade(grade, fromDate));
    }

    /** The person's own terms from the beginning of time; null when they have none. */
    public Integer getTerms() {
        return terms;
    }

    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    /** The person's dated terms by from-date. */
    public List<PersonTerms> getDatedTerms() {
        return datedTerms.stream().sorted(Comparator.comparing(PersonTerms::getFromDate)).toList();
    }

    /**
     * Record the terms (null: none) from the date, replacing the ones recorded from that same date; with no date,
     * from the beginning of time.
     */
    public void setTerms(Integer terms, LocalDate fromDate) {
        if (fromDate == null) {
            this.terms = terms;
            return;
        }
        for (PersonTerms t : datedTerms) {
            if (t.getFromDate().equals(fromDate)) {
                t.setTerms(terms);
                return;
            }
        }
        datedTerms.add(new PersonTerms(terms, fromDate));
    }

    /** The person's own terms in force on the date: the ones with the latest from-date on or before it; null for none. */
    public Integer termsOn(LocalDate date) {
        Integer inForce = terms;
        for (PersonTerms t : getDatedTerms()) {
            if (date != null && !t.getFromDate().isAfter(date)) {
                inForce = t.getTerms();
            }
        }
        return inForce;
    }

    public String getContact() {
        return contact;
    }

    /** Keeps the contact trimmed and lower-cased; a blank one is none. */
    public void setContact(String contact) {
        this.contact = contactKey(contact);
    }

    /** The contact as kept: trimmed and lower-cased; null when blank. */
    public static String contactKey(String contact) {
        return contact == null || contact.isBlank() ? null : contact.trim().toLowerCase(Locale.ROOT);
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    /** A person with a retirement date counts as retired at once, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    public String getOriginSheet() {
        return originSheet;
    }

    public void setOriginSheet(String originSheet) {
        this.originSheet = originSheet;
    }

    public LocalDate getTransferredOn() {
        return transferredOn;
    }

    public void setTransferredOn(LocalDate transferredOn) {
        this.transferredOn = transferredOn;
    }

    public long getPosition() {
        return position;
    }

    public void setPosition(long position) {
        this.position = position;
    }

    public LocalDate getAddedOn() {
        return addedOn;
    }

    public void setAddedOn(LocalDate addedOn) {
        this.addedOn = addedOn;
    }

    /** Record the retirement from the date, recorded while the sheet's today was {@code on}. */
    public void retire(LocalDate retiredFrom, LocalDate on) {
        this.retiredFrom = retiredFrom;
        this.retiredOn = on;
    }

    /** End the retirement on the date (the sheet's today), keeping it as a past retirement. */
    public void unretire(LocalDate on) {
        retirements.add(new PersonRetirement(retiredFrom, on));
        this.retiredFrom = null;
        this.retiredOn = null;
    }

    /** The person's past retirements by from-date, then to-date. */
    public List<PersonRetirement> getRetirements() {
        return retirements.stream()
                .sorted(Comparator.comparing(PersonRetirement::getFromDate).thenComparing(PersonRetirement::getToDate))
                .toList();
    }

    /** Whether the person had been added by the end of the date: added while the sheet's today was on or before it. */
    public boolean addedBy(LocalDate date) {
        return addedOn == null || !addedOn.isAfter(date);
    }

    /**
     * Whether the person's retirement date is reached on the date as they stood at the end of it: retired by a
     * retirement recorded while the sheet's today was on or before it, from that date or earlier.
     */
    public boolean retiredAsOf(LocalDate date) {
        return retiredOn(date) && (retiredOn == null || !retiredOn.isAfter(date));
    }

    /** Whether the date is within a hold the person had by the end of {@code asOf}. */
    public boolean heldAsOf(LocalDate date, LocalDate asOf) {
        return holds.stream().anyMatch(h -> h.madeBy(asOf) && h.covers(date));
    }

    /** Whether the person's retirement date is reached on the date: it is on or after it. */
    public boolean retiredOn(LocalDate date) {
        return retiredFrom != null && date != null && !date.isBefore(retiredFrom);
    }

    /** Whether the person has any hold. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** The person's holds by from-date, in the order they were added. */
    public List<PersonHold> getHolds() {
        return holds.stream().sorted(Comparator.comparing(PersonHold::getFromDate)).toList();
    }

    /** Add a hold from the date to the date (null: it does not end), added while the sheet's today was {@code on}. */
    public void addHold(LocalDate fromDate, LocalDate toDate, LocalDate on) {
        holds.add(new PersonHold(fromDate, toDate, on));
    }

    /** Whether the date is within any of the person's holds. */
    public boolean heldOn(LocalDate date) {
        return holds.stream().anyMatch(h -> h.covers(date));
    }

    /**
     * Whether the claim counts in the live totals: an OPEN, PENDING or SETTLED one, but an OPEN or PENDING one dated
     * within a hold, or once the person's retirement date is reached on the sheet's today, not unless a statement binds
     * it, and an OPEN one deferred on the sheet's today not at all.
     */
    public boolean counts(Claim claim, LocalDate today) {
        ClaimStatus status = claim.getStatus();
        return status.counts()
                && !(status.isOpen() && !claim.isBound() && (heldOn(claim.getDate()) || retiredOn(today)))
                && !claim.isDeferredOn(today);
    }

    /**
     * Whether the claim counts in a period's due-total, given the status it had at the end of the period's last day
     * (null when it was added after): an OPEN, PENDING or SETTLED one, but one open then and still open now and dated
     * within a hold, or once the person's retirement date is reached on the sheet's today, not unless a statement binds
     * it, and an OPEN one deferred on the sheet's today not at all.
     */
    public boolean countsAsOf(Claim claim, ClaimStatus status, LocalDate today) {
        return status != null && status.counts() && !(status.isOpen() && claim.getStatus().isOpen()
                && !claim.isBound() && (heldOn(claim.getDate()) || retiredOn(today))) && !claim.isDeferredOn(today);
    }

    /**
     * Refused when the person is retired and the date is after their retirement date, or the date is after the
     * from-date and before the to-date of a past retirement.
     */
    public void requireClaimable(LocalDate date) {
        if (retiredFrom != null && date.isAfter(retiredFrom)) {
            throw new PersonRetiredException(name, retiredFrom, date);
        }
        for (PersonRetirement r : retirements) {
            if (r.bars(date)) {
                throw new PersonRetiredException(name, r.getFromDate(), date);
            }
        }
    }

    /**
     * A copy of the person on another sheet: their grades, terms, contact, retirement and past retirements, holds,
     * place in the list, origin and the day they were added.
     */
    public Person copyTo(String sheetId) {
        List<PersonGrade> theirGrades = getGrades();
        Person copy = new Person(sheetId, name, theirGrades.get(0).getGrade());
        theirGrades.stream().skip(1).forEach(g -> copy.setGrade(g.getGrade(), g.getFromDate()));
        copy.terms = terms;
        datedTerms.forEach(t -> copy.datedTerms.add(new PersonTerms(t.getTerms(), t.getFromDate())));
        copy.contact = contact;
        copy.tags = tags;
        copy.retiredFrom = retiredFrom;
        copy.retiredOn = retiredOn;
        copy.addedOn = addedOn;
        copy.originSheet = originSheet;
        copy.position = position;
        holds.forEach(h -> copy.holds.add(new PersonHold(h.getFromDate(), h.getToDate(), h.getMadeOn())));
        retirements.forEach(r -> copy.retirements.add(new PersonRetirement(r.getFromDate(), r.getToDate())));
        return copy;
    }

    public long getVersion() {
        return version;
    }
}
