package net.officefloor.hq.app.person;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.officefloor.hq.app.claim.ClaimStatus;

/** A person on a sheet, with a name and dated grades (A, B or C), who may be retired from a date
 * and may be put on hold. */
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

    /** The grades recorded for the person, each in force from its from-date; the first has none. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    private List<PersonGrade> grades = new ArrayList<>();

    /** The date the person is retired from; null while they are not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** Whether the person is on hold: while held, their open claims count in no live total. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** The ids of the other people on the sheet this person names as their patrons. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_patron", joinColumns = @JoinColumn(name = "person_id"))
    @Column(name = "patron_id", nullable = false)
    private Set<Long> patronIds = new HashSet<>();

    /** Raised each time the person is saved. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        this.name = name;
        this.grades.add(new PersonGrade(null, grade));
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

    /** The grades recorded, from the first (from the beginning of time) to the latest from-date. */
    public List<PersonGrade> getGrades() {
        return grades.stream()
                .sorted(Comparator.comparing(PersonGrade::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** The grade in force on a date: the one with the latest from-date on or before it. */
    public String gradeOn(LocalDate date) {
        return grades.stream()
                .filter(g -> g.getFrom() == null || !g.getFrom().isAfter(date))
                .max(Comparator.comparing(PersonGrade::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(PersonGrade::getGrade)
                .orElse(null);
    }

    /** Record a grade from a date, replacing one already recorded from that date. */
    public void setGradeFrom(LocalDate from, String grade) {
        grades.removeIf(g -> Objects.equals(g.getFrom(), from));
        grades.add(new PersonGrade(from, grade));
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    /** Whether a claim dated on this date is refused: it is after the person's retirement date. */
    public boolean retiredOn(LocalDate date) {
        return retiredFrom != null && date.isAfter(retiredFrom);
    }

    public boolean isHeld() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    /** Whether a claim of this person in this status counts toward the totals: an open one not while held. */
    public boolean counts(ClaimStatus status) {
        return status.counts() && !(held && status.isOpen());
    }

    public Set<Long> getPatronIds() {
        return patronIds;
    }

    public long getVersion() {
        return version;
    }
}
