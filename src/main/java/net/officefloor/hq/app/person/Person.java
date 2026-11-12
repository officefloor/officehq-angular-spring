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
import java.util.Objects;

/** A person on a sheet, with a name and a dated grade (A, B or C): each grade they are given applies
 *  from a date, the first from the beginning of time. */
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

    /** The grades they were given, each from a date; the first has none. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private List<PersonGrade> grades = new ArrayList<>();

    /** The date they are retired from; null while not retired. Set at all, they count as retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** On hold: their OPEN and PENDING claims count in no live total; their SETTLED ones still do. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** Moved on by each save, so a save made from an older version is refused. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        this.name = name;
        this.grades.add(new PersonGrade(grade, null));
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

    /** The grades they were given, the one from the beginning of time first, then by from-date. */
    public List<PersonGrade> getGrades() {
        return grades.stream()
                .sorted(Comparator.comparing(PersonGrade::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** The grade in force on a date: the one whose from-date is the latest on or before it. */
    public String gradeOn(LocalDate date) {
        String found = null;
        for (PersonGrade g : getGrades()) {
            if (found == null || g.getFromDate() == null || !g.getFromDate().isAfter(date)) {
                found = g.getGrade();
            }
        }
        return found;
    }

    /** Give them a grade from a date, replacing the one they had from that same date. */
    public void setGrade(String grade, LocalDate from) {
        for (PersonGrade g : grades) {
            if (Objects.equals(g.getFromDate(), from)) {
                g.setGrade(grade);
                return;
            }
        }
        grades.add(new PersonGrade(grade, from));
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

    /** Whether a claim on this date is refused: it is after their retirement date. */
    public boolean retiredOn(LocalDate date) {
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
