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
import java.util.Locale;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

/**
 * A person on a sheet: a name and a dated grade (A, B or C): each grade they are given is in force
 * from its from-date, the first from the beginning of time (see {@link #gradeOn}). The name is kept without the spaces around
 * it and is unique on the sheet without regard to case, compared through its {@link #keyOf key}.
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

    /** The grades the person has been given, at most one per from-date. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    private List<PersonGrade> grades = new ArrayList<>();

    /** The date the person is retired from; null while they are not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** The holds the person has been put on: their OPEN and PENDING claims dated within one count in no
     * live total, unless a statement binds them. The same hold may be put on more than once. Loaded by
     * a sub-select, so it loads eagerly beside the grades. */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    private List<PersonHold> holds = new ArrayList<>();

    /** The person's own terms in days, in place of their grade's and the sheet's; null when they have none. */
    @Column(name = "terms")
    private Integer terms;

    /** Raised each time the person is saved. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        setName(name);
        this.grades.add(new PersonGrade(grade, null));
    }

    /** The form of a name that is unique on a sheet: without surrounding spaces, in lower case. */
    public static String keyOf(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
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
        this.name = name.trim();
        this.nameKey = keyOf(name);
    }

    /** The grades the person has been given, by from-date (the one with none first). */
    public List<PersonGrade> getGrades() {
        return grades.stream()
                .sorted(Comparator.comparing(PersonGrade::getFrom, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
    }

    /** Gives the person {@code grade} from {@code from}, in place of one given from the same date. */
    public void setGrade(String grade, LocalDate from) {
        grades.removeIf(g -> from.equals(g.getFrom()));
        grades.add(new PersonGrade(grade, from));
    }

    /** The grade in force on {@code date}: the one with the latest from-date on or before it, or the
     * first one given when none has; the latest given when {@code date} is null. */
    public String gradeOn(LocalDate date) {
        PersonGrade best = null;
        for (PersonGrade g : getGrades()) {
            if (date != null && g.getFrom() != null && g.getFrom().isAfter(date)) continue;
            best = g;
        }
        return best == null ? null : best.getGrade();
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

    /** Retired as soon as a retirement date is set, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** Whether the person's retirement date is reached on {@code today}: on or after it. */
    public boolean isRetiredOn(LocalDate today) {
        return retiredFrom != null && today != null && !today.isBefore(retiredFrom);
    }

    /** The holds the person has been put on, by from-date. */
    public List<PersonHold> getHolds() {
        return holds.stream()
                .sorted(Comparator.comparing(PersonHold::getFrom)
                        .thenComparing(PersonHold::getTo, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Puts the person on {@code hold}. */
    public void addHold(PersonHold hold) {
        holds.add(hold);
    }

    /** Whether the person has any hold, whatever its dates. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** Whether {@code date} falls within any of the person's holds. */
    public boolean isHeldOn(LocalDate date) {
        return holds.stream().anyMatch(h -> h.covers(date));
    }

    /** Refuses a claim dated {@code date} when it falls after the person's retirement date. */
    public void requireClaimable(LocalDate date) {
        if (retiredFrom != null && date.isAfter(retiredFrom)) {
            throw new PersonRetiredException();
        }
    }

    public long getVersion() {
        return version;
    }
}
