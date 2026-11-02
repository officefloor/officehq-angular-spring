package net.officefloor.hq.app.person;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.Entity;
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

/** A person on a sheet: a name and a grade (A, B or C), dated: each grade applies from its from-date. */
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

    /** The grades the person was given, each from its from-date; the first has none. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    private List<GradeFrom> grades = new ArrayList<>();

    /** The day the person is retired from; null while they are not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** Whether the person is on hold: their open claims then count in no live total. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** Raised each time the person is saved. */
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

    /** The grades the person was given, earliest from-date first, the one with none first of all. */
    public List<GradeFrom> getGrades() {
        return grades.stream()
                .sorted(Comparator.comparing(GradeFrom::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** The grade in force on a day: the one with the latest from-date on or before it, or else the
     *  one with no from-date. */
    public String gradeOn(LocalDate day) {
        String on = null;
        for (GradeFrom g : getGrades()) {
            if (g.getFromDate() == null || !g.getFromDate().isAfter(day)) {
                on = g.getGrade();
            }
        }
        return on;
    }

    /** Record a grade from a day, replacing the one recorded for that day, keeping the others. */
    public void setGradeFrom(String grade, LocalDate from) {
        grades.removeIf(g -> from.equals(g.getFromDate()));
        grades.add(new GradeFrom(from, grade));
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    /** Whether the person is retired: they are as soon as a retirement day is set, whatever the day. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    public boolean isHeld() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    public long getVersion() {
        return version;
    }
}
