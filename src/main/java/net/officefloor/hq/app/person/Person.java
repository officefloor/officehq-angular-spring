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
import net.officefloor.hq.app.claim.ClaimStatus;

/** A person on a sheet: a name and their grades (A, B or C), each from a from-date, and the date they are retired from, if any;
 * and whether they are on hold. */
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

    /** Set once retired: the person counts as retired at once, whatever the date. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** While held, the person's OPEN claims count in no live total. */
    @Column(name = "held", nullable = false)
    private boolean held;

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

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    public boolean isRetired() {
        return retiredFrom != null;
    }

    public boolean isHeld() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    /** Whether a claim of this person in the given status counts toward the live totals. */
    public boolean counts(ClaimStatus status) {
        return status.counts() && !(held && status == ClaimStatus.OPEN);
    }

    public long getVersion() {
        return version;
    }
}
