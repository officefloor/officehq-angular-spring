package net.officefloor.hq.app.person;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** A person on a sheet: a name and a grade (A, B or C), dated: each grade applies from its from-date. */
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

    /** The grades the person was given, each from its from-date; the first has none. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    private List<GradeFrom> grades = new ArrayList<>();

    /** The day the person is retired from; null while they are not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** The person's holds: their open claims dated within one count in no live total. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    private List<Hold> holds = new ArrayList<>();

    /** The person's own terms in days; null when they have none. */
    @Column(name = "terms")
    private Integer terms;

    /** The person's contact, trimmed and lower-cased; null when they have none. */
    @Column(name = "contact")
    private String contact;

    /** The person's aliases, each trimmed; unique on the sheet among names and aliases as names are
     *  compared. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_alias", joinColumns = @JoinColumn(name = "person_id"))
    @Column(name = "alias")
    private Set<String> aliases = new HashSet<>();

    /** Raised each time the person is saved. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    /** A detached person with the id given and no grade yet, for a revision to fill in (see
     *  {@link PersonRevision#toPerson}). */
    Person(String sheetId, Long id, String name) {
        this.sheetId = sheetId;
        this.id = id;
        this.name = name;
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        this.name = name;
        this.grades.add(new GradeFrom(null, grade));
    }

    /** A copy of the person for another sheet (they were transferred there): their name, every
     *  grade they were given (and its from-date), terms, contact and aliases; no holds. */
    public Person transferredTo(String toSheetId) {
        Person copy = new Person(toSheetId, (Long) null, name);
        grades.forEach(g -> copy.grades.add(new GradeFrom(g.getFromDate(), g.getGrade())));
        copy.terms = terms;
        copy.contact = contact;
        copy.aliases.addAll(aliases);
        return copy;
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

    /** The grades the person was given, earliest from-date first, the one with none first of all. */
    public List<GradeFrom> getGrades() {
        return grades.stream()
                .sorted(Comparator.comparing(GradeFrom::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** The grade in force on a day: the one with the latest from-date on or before it, or else the
     *  one with no from-date. */
    public String gradeOn(LocalDate day) {
        String on = null;
        for (GradeFrom g : getGrades()) {
            if (g.getFromDate() == null || !g.getFromDate().isAfter(day)) {
                on = g.getGrade();
            }
        }
        return on;
    }

    /** Record a grade from a day (null: the beginning of time), replacing the one recorded for that
     *  day, keeping the others. */
    public void setGradeFrom(String grade, LocalDate from) {
        grades.removeIf(g -> Objects.equals(from, g.getFromDate()));
        grades.add(new GradeFrom(from, grade));
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    /** Whether the person is retired: they are as soon as a retirement day is set, whatever the day. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** Whether the person is retired on a day: their retirement day is set and the day is on or after it.
     *  From then their open claims unbound by a statement count in no live total. */
    public boolean isRetiredOn(LocalDate day) {
        return retiredFrom != null && !day.isBefore(retiredFrom);
    }

    /** The person's holds, earliest from-date first, those with none first of all. */
    public List<Hold> getHolds() {
        return holds.stream()
                .sorted(Comparator.comparing(Hold::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** Whether the person has any hold, whatever its days. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** Whether one of the person's holds covers a day. */
    public boolean isHeldOn(LocalDate day) {
        return holds.stream().anyMatch(h -> h.covers(day));
    }

    /** Add a hold, keeping the others. */
    public void addHold(LocalDate from, LocalDate to) {
        holds.add(new Hold(from, to));
    }

    /** The person's own terms in days, ahead of their grade's and the sheet's; null when none. */
    public Integer getTerms() {
        return terms;
    }

    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    /** The person's contact, held by no one else on the sheet; null when they have none. */
    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    /** The person's aliases, sorted as names are compared: without regard to case. */
    public List<String> getAliases() {
        return aliases.stream()
                .sorted(Comparator.comparing((String a) -> a.toLowerCase(Locale.ROOT)).thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    /** Add an alias, keeping the others. */
    public void addAlias(String alias) {
        aliases.add(alias);
    }

    public long getVersion() {
        return version;
    }
}
