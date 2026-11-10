package net.officefloor.hq.app.person;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;

/** A person on a sheet: a name and their grades (A, B or C), each from a from-date, and the date they are retired from, if any;
 * and the holds they have had, each over a span of dates. */
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

    /** The grades recorded, the first from the beginning of time (no from-date), the others each from a date. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    private List<DatedGrade> grades = new ArrayList<>();

    /** The person's own terms in days, or null when they have none. */
    @Column(name = "terms")
    private Integer terms;

    /** Set once retired: the person counts as retired at once, whatever the date. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** The person's OPEN claims dated within a hold, and not bound by a statement, count in no live total. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    private List<DatedHold> holds = new ArrayList<>();

    /** Bumped on every save; an edit must carry the version it was made from. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        this.name = name;
        this.grades.add(new DatedGrade(null, grade));
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

    public void setName(String name) {
        this.name = name;
    }

    /** The grades recorded, the one from the beginning of time first and then by from-date. */
    public List<DatedGrade> getGrades() {
        return grades.stream().sorted(Comparator.comparing(DatedGrade::getFromDate,
                Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
    }

    /** The grade in force on a date: the one with the latest from-date on or before it. */
    public String gradeOn(LocalDate date) {
        String found = null;
        for (DatedGrade g : getGrades()) {
            if (g.getFromDate() == null || !g.getFromDate().isAfter(date)) {
                found = g.getGrade();
            }
        }
        return found;
    }

    /** Record the grade from a date, replacing any grade already recorded from that same date. */
    public void setGradeFrom(LocalDate fromDate, String grade) {
        grades.removeIf(g -> fromDate.equals(g.getFromDate()));
        grades.add(new DatedGrade(fromDate, grade));
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

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** Whether the retirement date is reached on a day (the day on or after it). */
    public boolean retiredOn(LocalDate date) {
        return retiredFrom != null && date != null && !date.isBefore(retiredFrom);
    }

    /** The holds recorded, by from-date. */
    public List<DatedHold> getHolds() {
        return holds.stream().sorted(Comparator.comparing(DatedHold::getFromDate)
                .thenComparing(DatedHold::getToDate, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    public void addHold(LocalDate fromDate, LocalDate toDate) {
        holds.add(new DatedHold(fromDate, toDate));
    }

    /** Whether the person has any hold. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** Whether a day lies within one of the person's holds. */
    public boolean heldOn(LocalDate date) {
        return holds.stream().anyMatch(h -> h.covers(date));
    }

    /**
     * Whether a claim of this person counts toward the live totals: an open claim dated within a
     * hold does not, nor does one of a person whose retirement date the sheet's today has reached,
     * unless a statement binds it; nor does a claim deferred on the sheet's today.
     */
    public boolean counts(Claim claim, LocalDate today) {
        return counts(claim, claim.getStatus(), today);
    }

    /**
     * Whether a claim of this person counts by the same rules, had it the given status; a hold or a
     * reached retirement takes out only a claim that is open now as well (one since settled or voided is not).
     */
    public boolean counts(Claim claim, ClaimStatus status, LocalDate today) {
        return status.counts() && !claim.isDeferredOn(status, today) && (claim.isStated()
                || !(status.isOpen() && claim.getStatus().isOpen() && (heldOn(claim.getDate()) || retiredOn(today))));
    }

    public long getVersion() {
        return version;
    }
}
