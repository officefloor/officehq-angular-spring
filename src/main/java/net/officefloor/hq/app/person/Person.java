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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A person on a sheet, with a name and a grade (A, B or C). The name is kept without surrounding
 * spaces and is unique on the sheet without regard to case (by its {@link #keyOf key}). A person
 * given a retirement date is retired at once, whatever the date; none of their claims may be dated
 * after it. A person can be put on hold and taken off it again. The grade is dated: the first one
 * given is in force from the beginning of time, and each later one from its from-date.
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

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(name = "grade", nullable = false)
    private String grade;

    /** The grades recorded after the first, by the date each is in force from. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    @MapKeyColumn(name = "from_date")
    @Column(name = "grade", nullable = false)
    private Map<LocalDate, String> datedGrades = new TreeMap<>();

    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    @Column(name = "held", nullable = false)
    private boolean held;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {}

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        setName(name);
        this.grade = grade;
    }

    /** The key a name is compared by for uniqueness: without surrounding spaces, lower-cased. */
    public static String keyOf(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public String getName() { return name; }

    public void setName(String name) {
        this.name = name.strip();
        this.nameKey = keyOf(name);
    }

    /** The first grade they were given, in force from the beginning of time. */
    public String getGrade() { return grade; }

    /** The grade in force on the date: the one with the latest from-date on or before it. */
    public String gradeOn(LocalDate date) {
        Map.Entry<LocalDate, String> on = new TreeMap<>(datedGrades).floorEntry(date);
        return on == null ? grade : on.getValue();
    }

    /** Record the grade from the date, in place of any recorded from the same date. */
    public void setGradeFrom(LocalDate from, String grade) { datedGrades.put(from, grade); }

    /** The grades recorded after the first, by the date each is in force from, earliest first. */
    public Map<LocalDate, String> getDatedGrades() { return new TreeMap<>(datedGrades); }

    /** The date they are retired from, or null while not retired. */
    public LocalDate getRetiredFrom() { return retiredFrom; }

    public void setRetiredFrom(LocalDate retiredFrom) { this.retiredFrom = retiredFrom; }

    public boolean isRetired() { return retiredFrom != null; }

    /** Whether a claim of theirs may be dated on the date: not after their retirement date. */
    public boolean mayClaimOn(LocalDate date) { return retiredFrom == null || !date.isAfter(retiredFrom); }

    /** While held, their OPEN claims count in no live total; their SETTLED claims still do. */
    public boolean isHeld() { return held; }

    public void setHeld(boolean held) { this.held = held; }

    /** Moved on by each save; an edit is refused unless made from the current version. */
    public long getVersion() { return version; }
}
