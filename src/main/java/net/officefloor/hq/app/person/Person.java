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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A person on a sheet, with a name and their grades (A, B or C), each from a date (see {@link #gradeOn}), the date they are retired from (null while
 * not retired), whether they are on hold and the version it was last saved at.
 */
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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    private List<GradeFrom> grades = new ArrayList<>();

    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    @Column(name = "held", nullable = false)
    private boolean held;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        this.name = name;
        this.grades.add(new GradeFrom(grade, null));
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

    public long getVersion() {
        return version;
    }

    /**
     * The grade in force on the date: the one whose from-date is the latest on or before it, the first
     * grade (no from-date) applying from the beginning of time.
     */
    public String gradeOn(LocalDate date) {
        return grades.stream()
                .filter(g -> g.getFromDate() == null || !g.getFromDate().isAfter(date))
                .max(Comparator.comparing(GradeFrom::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(GradeFrom::getGrade).orElse(null);
    }

    /** Record the grade from the date, keeping the others; one already recorded from that date is replaced. */
    public void gradeFrom(String grade, LocalDate from) {
        grades.removeIf(g -> g.getFromDate() != null && Objects.equals(g.getFromDate(), from));
        grades.add(new GradeFrom(grade, from));
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    /** Whether the person is on hold: their OPEN claims then count in no live total. */
    public boolean isHeld() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    /** Whether a retirement date is set: retired at once, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }
}
