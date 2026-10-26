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
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;

/**
 * A person on a sheet, with a name (unique on the sheet, ignoring case) and a dated grade (A, B or C): each grade
 * applies from its from-date, the first one given from the beginning of time; and the terms of their own, if any.
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

    @Column(name = "terms")
    private Integer terms;

    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** The sheet's today when the person was added; null: before that was kept. */
    @Column(name = "added_on")
    private LocalDate addedOn;

    /** The sheet's today when the retirement date was set; null: before that was kept, or not retired. */
    @Column(name = "retired_on")
    private LocalDate retiredOn;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    private List<DatedHold> holds = new ArrayList<>();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this(sheetId, name, grade, null);
    }

    /** A person added while the sheet's today was the date. */
    public Person(String sheetId, String name, String grade, LocalDate addedOn) {
        this.sheetId = sheetId;
        this.name = name;
        this.addedOn = addedOn;
        this.grades.add(new DatedGrade(grade, null, addedOn));
    }

    /**
     * The person as they stood at the end of the day, for figures taken as of it: only the grades, holds and
     * retirement recorded while the sheet's today was on or before the day. Not to be saved.
     */
    public Person asOf(LocalDate day) {
        Person then = new Person();
        then.id = id;
        then.sheetId = sheetId;
        then.name = name;
        then.terms = terms;
        then.addedOn = addedOn;
        then.version = version;
        grades.stream().filter(g -> g.madeBy(day)).forEach(then.grades::add);
        if (then.grades.isEmpty()) {
            // the first grade they were given stands for them
            grades.stream().filter(g -> g.getFrom() == null).forEach(then.grades::add);
        }
        holds.stream().filter(h -> h.madeBy(day)).forEach(then.holds::add);
        if (retiredOn == null || !retiredOn.isAfter(day)) {
            then.retiredFrom = retiredFrom;
            then.retiredOn = retiredOn;
        }
        return then;
    }

    /** Whether the person had been added by the end of the day: added while the sheet's today was on or before it. */
    public boolean addedBy(LocalDate day) {
        return addedOn == null || !addedOn.isAfter(day);
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
    public void recordGrade(String grade, LocalDate from, LocalDate madeOn) {
        grades.removeIf(g -> Objects.equals(g.getFrom(), from));
        grades.add(new DatedGrade(grade, from, madeOn));
    }

    /** The person's own terms in days: a claim of theirs is due by its date plus these; null for none. */
    public Integer getTerms() {
        return terms;
    }

    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    /** The date the person is retired from; null while they are not retired. */
    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    /** Retires the person from the date, set while the sheet's today was the made-on date. */
    public void setRetiredFrom(LocalDate retiredFrom, LocalDate madeOn) {
        this.retiredFrom = retiredFrom;
        this.retiredOn = madeOn;
    }

    /** A person with a retirement date set is retired at once, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** Whether the person's retirement date is reached on the day: the day is on or after it. */
    public boolean isRetiredOn(LocalDate day) {
        return retiredFrom != null && day != null && !day.isBefore(retiredFrom);
    }

    /** Every hold put on the person, earliest from-date first. */
    public List<DatedHold> getHolds() {
        return holds.stream().sorted(Comparator.comparing(DatedHold::getFrom)).toList();
    }

    /** Whether the person has any hold. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** Puts a hold on the person from the date to the date (null: with no end); the ones already put are kept. */
    public void addHold(LocalDate from, LocalDate to, LocalDate madeOn) {
        holds.add(new DatedHold(from, to, madeOn));
    }

    /**
     * Whether the claim counts toward the person's live totals: one whose status counts, except an open one dated
     * within a hold that no statement binds, or once the person's retirement date is reached on the sheet's today, and
     * one deferred on the sheet's today.
     */
    public boolean countsLive(Claim claim, LocalDate today) {
        return claim.getStatus().counts() && !claim.isDeferredOn(today)
                && !(claim.getStatus().isOpen() && !claim.isBound()
                && (holds.stream().anyMatch(h -> h.covers(claim.getDate())) || isRetiredOn(today)));
    }

    /**
     * Whether the claim counts toward the person's figure for a period ending on the day: one whose status at the end
     * of that day counts, left out as the live rules say when deferred on the sheet's today or held.
     */
    public boolean countsAtEndOf(Claim claim, LocalDate end, LocalDate today) {
        ClaimStatus then = claim.statusAtEndOf(end);
        return then != null && then.counts() && !claim.isDeferredOn(today)
                && !(claim.getStatus().isOpen() && !claim.isBound()
                && (holds.stream().anyMatch(h -> h.covers(claim.getDate())) || isRetiredOn(today)));
    }

    /** The version of the person as stored, raised on each save. */
    public long getVersion() {
        return version;
    }
}
