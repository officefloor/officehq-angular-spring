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

    /** The grades they were given, each from its date; the first one from the beginning of time. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    private List<GradeFrom> grades = new ArrayList<>();

    /** Retired from this date (claims dated after it are refused); null when not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** On hold: their open claims count in no live total (settled ones still do). */
    @Column(name = "held", nullable = false)
    private boolean held;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {}

    public Person(String sheetId, String name, Grade grade) {
        this.sheetId = sheetId;
        this.name = name;
        this.grades.add(new GradeFrom(grade, null));
    }

    public Long getId() { return id; }

    public long getVersion() { return version; }

    public String getSheetId() { return sheetId; }

    public String getName() { return name; }

    public void setName(String name) { this.name = name; }

    /** The grades they were given, by from-date (the one from the beginning of time first). */
    public List<GradeFrom> getGrades() {
        return grades.stream()
                .sorted(Comparator.comparing(GradeFrom::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** The grade in force on {@code date}: the one with the latest from-date on or before it. */
    public Grade gradeOn(LocalDate date) {
        return grades.stream()
                .filter(g -> g.appliesOn(date))
                .max(Comparator.comparing(GradeFrom::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(GradeFrom::getGrade)
                .orElseThrow();
    }

    /** Give them a grade from a date, replacing the one they had from that date; the others are kept. */
    public void setGradeFrom(Grade grade, LocalDate from) {
        grades.removeIf(g -> Objects.equals(g.getFromDate(), from));
        grades.add(new GradeFrom(grade, from));
    }

    public LocalDate getRetiredFrom() { return retiredFrom; }

    public void setRetiredFrom(LocalDate retiredFrom) { this.retiredFrom = retiredFrom; }

    public boolean isHeld() { return held; }

    public void setHeld(boolean held) { this.held = held; }

    /** Whether a claim dated {@code date} is after their retirement. */
    public boolean retiredOn(LocalDate date) { return retiredFrom != null && date.isAfter(retiredFrom); }
}
