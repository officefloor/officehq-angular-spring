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

    /** Their own terms in days, ahead of their grade's and the sheet's; null when they have none. */
    @Column(name = "terms")
    private Integer terms;

    /** The date they are retired from; null while not retired. Set at all, they count as retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** Their holds: an OPEN or PENDING claim dated within one, and bound by no statement, counts in no
     *  live total; their other claims still do. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private List<PersonHold> holds = new ArrayList<>();

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

    public Integer getTerms() {
        return terms;
    }

    public void setTerms(Integer terms) {
        this.terms = terms;
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

    /** Whether their retirement date is reached on a day (the sheet's today): it is on or before it. */
    public boolean retiredBy(LocalDate today) {
        return retiredFrom != null && today != null && !today.isBefore(retiredFrom);
    }

    /** Whether they have any hold at all. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** Their holds, the one from the beginning of time first, then by from-date. */
    public List<PersonHold> getHolds() {
        return holds.stream()
                .sorted(Comparator.comparing(PersonHold::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(PersonHold::getToDate, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Whether a hold of theirs covers a date. */
    public boolean heldOn(LocalDate date) {
        return holds.stream().anyMatch(h -> h.covers(date));
    }

    /** Put them on hold from a date, to a date when one is given. */
    public void hold(LocalDate from, LocalDate to) {
        holds.add(new PersonHold(from, to));
    }

    public long getVersion() {
        return version;
    }
}
