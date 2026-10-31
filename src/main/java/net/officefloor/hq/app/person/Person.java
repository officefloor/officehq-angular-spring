package net.officefloor.hq.app.person;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.officefloor.hq.app.claim.ClaimStatus;

/** A person on a sheet: a name and a grade (A, B or C), dated: each grade is in force from its from-date. */
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

    /** The grades recorded for the person; the first given has no from-date (from the beginning of time). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    private List<GradeFrom> grades = new ArrayList<>();

    /** The date the person is retired from; null while they are not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** Whether the person is on hold: while held, their OPEN claims count in no live total. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** Raised on every save; an edit made from an older version is refused. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        this.name = name;
        this.grades.add(new GradeFrom(null, grade));
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

    /** The grades recorded, in the order they take effect. */
    public List<GradeFrom> getGrades() {
        return grades.stream().sorted(GradeFrom.BY_FROM).toList();
    }

    /** The grade in force on {@code date}: the one with the latest from-date on or before it. A null date
     *  takes the latest grade recorded. */
    public String gradeOn(LocalDate date) {
        return grades.stream()
                .filter(g -> date == null || g.getFromDate() == null || !g.getFromDate().isAfter(date))
                .max(GradeFrom.BY_FROM)
                .orElseGet(() -> grades.stream().min(GradeFrom.BY_FROM).orElseThrow())
                .getGrade();
    }

    /** Record {@code grade} from {@code from}, replacing the grade recorded from that same date, if any. */
    public void setGradeFrom(LocalDate from, String grade) {
        grades.removeIf(g -> Objects.equals(g.getFromDate(), from));
        grades.add(new GradeFrom(from, grade));
    }

    public long getVersion() {
        return version;
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    /** Retired as soon as a retirement date is set, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    public boolean isHeld() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    /** Whether a claim of this person in {@code status} counts in the live totals: while held, only SETTLED ones do. */
    public boolean counts(ClaimStatus status) {
        return held ? status == ClaimStatus.SETTLED : status.counts();
    }

    /** Whether a claim dated {@code date} is refused: one dated after the retirement date. */
    public boolean refusesClaimOn(LocalDate date) {
        return retiredFrom != null && date.isAfter(retiredFrom);
    }
}
