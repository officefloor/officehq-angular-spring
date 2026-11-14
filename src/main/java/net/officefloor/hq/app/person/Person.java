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
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A person on a sheet, with a name and a dated grade (A, B or C). */
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

    /** The first grade the person was given: in force from the beginning of time until a later one. */
    @Column(name = "grade", nullable = false)
    private String grade;

    /** The grades recorded since, by the date each is in force from. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    @MapKeyColumn(name = "from_date")
    @Column(name = "grade", nullable = false)
    private Map<LocalDate, String> datedGrades = new HashMap<>();

    /** The date the person is retired from; null while not retired. Set, they count as retired
     *  at once, whatever the date. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** Whether the person is on hold: their OPEN claims then count in no live total. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** Raised on every save, so an edit of a version someone else has since saved is refused. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        this.name = name;
        this.grade = grade;
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

    /** A grade and the date it is in force from (null: from the beginning of time). */
    public record DatedGrade(String grade, LocalDate from) {
    }

    /** The grade in force on a date: the one with the latest from-date on or before it, else the first. */
    public String gradeOn(LocalDate on) {
        Map.Entry<LocalDate, String> latest = new TreeMap<>(datedGrades).floorEntry(on);
        return latest == null ? grade : latest.getValue();
    }

    /** Record a grade from a date, in place of any recorded from the same date. */
    public void recordGrade(String grade, LocalDate from) {
        datedGrades.put(from, grade);
    }

    /** Every grade the person has been given, by from-date, the first one (undated) leading. */
    public List<DatedGrade> getGrades() {
        List<DatedGrade> out = new ArrayList<>();
        out.add(new DatedGrade(grade, null));
        new TreeMap<>(datedGrades).forEach((from, g) -> out.add(new DatedGrade(g, from)));
        return out;
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

    /** Whether a claim dated {@code date} is refused for the person: one after their retirement date. */
    public boolean retiredBefore(LocalDate date) {
        return retiredFrom != null && date.isAfter(retiredFrom);
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
