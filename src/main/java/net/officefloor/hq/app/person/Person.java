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

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    private List<DatedHold> holds = new ArrayList<>();

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

    /** Every hold put on the person, earliest from-date first. */
    public List<DatedHold> getHolds() {
        return holds.stream().sorted(Comparator.comparing(DatedHold::getFrom)).toList();
    }

    /** Whether the person has any hold. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** Puts a hold on the person from the date to the date (null: with no end); the ones already put are kept. */
    public void addHold(LocalDate from, LocalDate to) {
        holds.add(new DatedHold(from, to));
    }

    /**
     * Whether the claim counts toward the person's live totals: one whose status counts, except an open one dated
     * within a hold that no statement binds, and one deferred on the sheet's today.
     */
    public boolean countsLive(Claim claim, LocalDate today) {
        return claim.getStatus().counts() && !claim.isDeferredOn(today)
                && !(claim.getStatus().isOpen() && !claim.isBound()
                && holds.stream().anyMatch(h -> h.covers(claim.getDate())));
    }

    /**
     * Whether the claim counts toward the person's figure for a period ending on the day: one whose status at the end
     * of that day counts, left out as the live rules say when deferred on the sheet's today or held.
     */
    public boolean countsAtEndOf(Claim claim, LocalDate end, LocalDate today) {
        ClaimStatus then = claim.statusAtEndOf(end);
        return then != null && then.counts() && !claim.isDeferredOn(today)
                && !(claim.getStatus().isOpen() && !claim.isBound()
                && holds.stream().anyMatch(h -> h.covers(claim.getDate())));
    }

    /** The version of the person as stored, raised on each save. */
    public long getVersion() {
        return version;
    }
}
