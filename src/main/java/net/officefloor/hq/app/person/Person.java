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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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

    /** The name as uniqueness compares it: trimmed and lower-cased. */
    @Column(name = "name_key", nullable = false)
    private String nameKey;

    /** Every grade the person was given, each from its from-date; the first from the beginning of time. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private List<PersonGrade> grades = new ArrayList<>();

    /** The date the person is retired from; null while they are not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** Every hold put on the person; their open claims dated within one, unless bound by a statement,
     *  count in no live total. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private Set<PersonHold> holds = new LinkedHashSet<>();

    /** Bumped on each save; an edit made from an older version is refused. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, Grade grade) {
        this.sheetId = sheetId;
        setName(name);
        grades.add(new PersonGrade(grade, null));
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

    /** Stores the name with its surrounding spaces removed. */
    public void setName(String name) {
        this.name = name.trim();
        this.nameKey = keyOf(name);
    }

    /** The key a name is compared on for uniqueness: without surrounding spaces or regard to case. */
    public static String keyOf(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    /** The grades the person was given, the one from the beginning of time first, then by from-date. */
    public List<PersonGrade> getGrades() {
        List<PersonGrade> ordered = new ArrayList<>(grades);
        ordered.sort((a, b) -> a.startsAfter(b) ? 1 : b.startsAfter(a) ? -1 : 0);
        return ordered;
    }

    /** The grade in force on a date: the one with the latest from-date on or before it. */
    public Grade gradeOn(LocalDate date) {
        PersonGrade inForce = null;
        for (PersonGrade g : grades) {
            if (g.appliesBy(date) && (inForce == null || g.startsAfter(inForce))) inForce = g;
        }
        return inForce == null ? null : inForce.getGrade();
    }

    /** Give the person a grade from a date, replacing the grade they had from that same date and
     *  keeping every other one. */
    public void setGrade(Grade grade, LocalDate from) {
        for (PersonGrade g : grades) {
            if (from.equals(g.getFrom())) {
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

    /** Whether the person is retired: true as soon as a retirement date is set, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** Whether a claim of this date is refused: one dated after the person's retirement date. */
    public boolean isRetiredOn(LocalDate date) {
        return retiredFrom != null && date.isAfter(retiredFrom);
    }

    /** The holds put on the person, the one from the beginning of time first, then by from-date, then
     *  in the order they were put on. */
    public List<PersonHold> getHolds() {
        List<PersonHold> ordered = new ArrayList<>(holds);
        ordered.sort(Comparator.comparing(PersonHold::getFrom, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(PersonHold::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        return ordered;
    }

    /** Whether the person has any hold, whatever its dates. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** Whether a date falls within one of the person's holds. */
    public boolean isHeldOn(LocalDate date) {
        return holds.stream().anyMatch(h -> h.covers(date));
    }

    /** Put a hold on the person from a date to a date (none: no end). */
    public void hold(LocalDate from, LocalDate to) {
        holds.add(new PersonHold(from, to));
    }

    public long getVersion() {
        return version;
    }
}
