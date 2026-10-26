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
import net.officefloor.hq.app.claim.ClaimStatus;

/**
 * A person on a sheet, with a name (unique on the sheet, ignoring case) and a dated grade (A, B or C): each grade
 * applies from its from-date, the first one given from the beginning of time.
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
    private List<DatedGrade> grades = new ArrayList<>();

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
        this.grades.add(new DatedGrade(grade, null));
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

    /** Every grade recorded for the person, earliest from-date first (the one from the beginning of time first). */
    public List<DatedGrade> getGrades() {
        return grades.stream().sorted(Comparator.comparing(DatedGrade::getFrom,
                Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
    }

    /** The grade in force on the date: the one with the latest from-date on or before it. */
    public String gradeOn(LocalDate date) {
        return grades.stream().filter(g -> g.getFrom() == null || (date != null && !g.getFrom().isAfter(date)))
                .max(Comparator.comparing(DatedGrade::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(DatedGrade::getGrade).orElse(null);
    }

    /** Records the grade from the date, replacing one already recorded from that same date; the others are kept. */
    public void recordGrade(String grade, LocalDate from) {
        grades.removeIf(g -> Objects.equals(g.getFrom(), from));
        grades.add(new DatedGrade(grade, from));
    }

    /** The date the person is retired from; null while they are not retired. */
    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    /** A person with a retirement date set is retired at once, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** Whether the person is on hold: while held, their open (and pending) claims count in no live total. */
    public boolean isHeld() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    /** Whether the claim of the given status counts toward the person's live totals: an open one not while held. */
    public boolean countsLive(ClaimStatus status) {
        return status.counts() && !(held && status.isOpen());
    }

    /** The version of the person as stored, raised on each save. */
    public long getVersion() {
        return version;
    }
}
