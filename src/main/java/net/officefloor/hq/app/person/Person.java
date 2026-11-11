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

    public Integer getTerms() {
        return terms;
    }

    public void setTerms(Integer terms) {
        this.terms = terms;
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

    /** Refused when the person is retired and the date is after their retirement date. */
    public void requireClaimable(LocalDate date) {
        if (retiredFrom != null && date.isAfter(retiredFrom)) {
            throw new PersonRetiredException(name, retiredFrom, date);
        }
    }

    public long getVersion() {
        return version;
    }
}
