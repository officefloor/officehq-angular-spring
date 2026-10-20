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

/** A person kept on a sheet, with a name and a grade (A, B or C) that is dated: each grade is in force from its
 * from-date until the next, the first one given from the beginning of time. */
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

    /** The person's own terms in days, ahead of their grade's and the sheet's; null when they have none. */
    @Column(name = "terms")
    private Integer terms;

    /** The date the person is retired from; null while not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** The sheet's today when the person was retired; null while not retired, or when not kept. */
    @Column(name = "retired_on")
    private LocalDate retiredOn;

    /** The sheet's today when the person was added; null when not kept. */
    @Column(name = "added_on")
    private LocalDate addedOn;

    /** The person's holds: their OPEN and PENDING claims dated within one, unless bound by a statement, count in no
     * live total. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    private List<Hold> holds = new ArrayList<>();

    /** Raised each time the person is saved. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {}

    public Person(String sheetId, String name, String grade, LocalDate addedOn) {
        this.sheetId = sheetId;
        this.name = name;
        this.addedOn = addedOn;
        this.grades.add(new GradeFrom(grade, null, addedOn));
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

    /** The person's grades by from-date, the first one given (with no from-date) first. */
    public List<GradeFrom> getGrades() {
        return grades.stream().sorted(GradeFrom.BY_FROM).toList();
    }

    /** The grade in force on a date: the one with the latest from-date on or before it. */
    public String gradeOn(LocalDate date) {
        return grades.stream()
                .filter(g -> g.getFrom() == null || !g.getFrom().isAfter(date))
                .max(GradeFrom.BY_FROM)
                .map(GradeFrom::getGrade)
                .orElseThrow();
    }

    /** Record the grade from a from-date, made on the sheet's today, replacing any the person had from that date and
     * keeping the others. */
    public void setGrade(String grade, LocalDate from, LocalDate madeOn) {
        grades.removeIf(g -> Objects.equals(g.getFrom(), from));
        grades.add(new GradeFrom(grade, from, madeOn));
    }

    public Integer getTerms() {
        return terms;
    }

    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    public long getVersion() {
        return version;
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    /** Retire the person from a date, made on the sheet's today. */
    public void retire(LocalDate retiredFrom, LocalDate madeOn) {
        this.retiredFrom = retiredFrom;
        this.retiredOn = madeOn;
    }

    public LocalDate getAddedOn() {
        return addedOn;
    }

    /** Whether the person was on the sheet at the end of a date: added while the sheet's today was on or before it. */
    public boolean addedBy(LocalDate date) {
        return addedOn == null || !addedOn.isAfter(date);
    }

    /** The person's holds by from-date. */
    public List<Hold> getHolds() {
        return holds.stream().sorted(Hold.BY_FROM).toList();
    }

    /** Whether the person has any hold. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** Whether a date is within one of the person's holds. */
    public boolean heldOn(LocalDate date) {
        return holds.stream().anyMatch(h -> h.covers(date));
    }

    /** Add a hold, made on the sheet's today. */
    public void addHold(LocalDate from, LocalDate to, LocalDate madeOn) {
        holds.add(new Hold(from, to, madeOn));
    }

    /** Whether the person's retirement date is reached on a date (the date is on or after it). */
    public boolean retiredBy(LocalDate date) {
        return retiredFrom != null && date != null && !date.isBefore(retiredFrom);
    }

    /** Whether a claim dated {@code date} is after the person's retirement. */
    public boolean retiredOn(LocalDate date) {
        return retiredFrom != null && date.isAfter(retiredFrom);
    }

    /**
     * The person as they stood at the end of a date, never saved: only the grades, holds and retirement recorded
     * while the sheet's today was on or before it (one kept without that date counts). A grade given later from a
     * from-date that replaced one is not restored; with none left, the first grade they were given stands.
     */
    public Person asOf(LocalDate date) {
        Person then = new Person();
        then.id = id;
        then.sheetId = sheetId;
        then.name = name;
        then.terms = terms;
        then.addedOn = addedOn;
        then.version = version;
        then.grades = new ArrayList<>(grades.stream().filter(g -> g.madeBy(date)).toList());
        if (then.grades.isEmpty()) {
            getGrades().stream().findFirst().ifPresent(then.grades::add);
        }
        if (retiredOn == null || !retiredOn.isAfter(date)) {
            then.retiredFrom = retiredFrom;
            then.retiredOn = retiredOn;
        }
        then.holds = new ArrayList<>(holds.stream().filter(h -> h.madeBy(date)).toList());
        return then;
    }
}
