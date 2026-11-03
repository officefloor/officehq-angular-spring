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
import jakarta.persistence.OrderColumn;
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
import net.officefloor.hq.app.claim.Tags;
import org.hibernate.annotations.OptimisticLock;

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

    /** The person's place in the sheet's list, smallest first; a new person goes to the end. */
    @Column(name = "position", nullable = false)
    private long position;

    /** The grades the person was given, each from its from-date; the first has none. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    private List<GradeFrom> grades = new ArrayList<>();

    /** The day the person is retired from; null while they are not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** The person's past retirements: each from the day they were retired from to the day they were
     *  unretired. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_retirement", joinColumns = @JoinColumn(name = "person_id"))
    private List<Retirement> retirements = new ArrayList<>();

    /** The person's holds: their open claims dated within one count in no live total. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    private List<Hold> holds = new ArrayList<>();

    /** The person's snapshots, oldest first; writing one does not raise the version. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_snapshot", joinColumns = @JoinColumn(name = "person_id"))
    @OrderColumn(name = "position")
    @OptimisticLock(excluded = true)
    private List<Snapshot> snapshots = new ArrayList<>();

    /** The person's own terms in days; null when they have none. */
    @Column(name = "terms")
    private Integer terms;

    /** The person's contact, trimmed and lower-cased; null when they have none. */
    @Column(name = "contact")
    private String contact;

    /** The person's tags, sorted and comma separated (see {@link Tags}); null when they have none. */
    @Column(name = "tags")
    private String tags;

    /** The person's aliases, each trimmed; unique on the sheet among names and aliases as names are
     *  compared. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_alias", joinColumns = @JoinColumn(name = "person_id"))
    @Column(name = "alias")
    private Set<String> aliases = new HashSet<>();

    /** The sheet the person was transferred from; null when they were not transferred. */
    @Column(name = "origin")
    private String origin;

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

    public Person(String sheetId, String name, String grade, long position) {
        this.sheetId = sheetId;
        this.name = name;
        this.position = position;
        this.grades.add(new GradeFrom(null, grade));
    }

    /** A copy of the person for another sheet (they were transferred there): their name, every
     *  grade they were given (and its from-date), terms, contact, tags and aliases; no holds. They take
     *  the place given in that sheet's list. */
    public Person transferredTo(String toSheetId, long toPosition) {
        Person copy = new Person(toSheetId, (Long) null, name);
        copy.position = toPosition;
        grades.forEach(g -> copy.grades.add(new GradeFrom(g.getFromDate(), g.getGrade())));
        copy.terms = terms;
        copy.contact = contact;
        copy.tags = tags;
        copy.aliases.addAll(aliases);
        copy.origin = sheetId;
        return copy;
    }

    /** A copy of the person for a clone of their sheet: their name, place in the list, every grade
     *  they were given (and its from-date), terms, contact, aliases, holds, retirement and past
     *  retirements, and the sheet they were transferred from. */
    public Person clonedTo(String toSheetId) {
        Person copy = transferredTo(toSheetId, position);
        copy.origin = origin;
        copy.retiredFrom = retiredFrom;
        retirements.forEach(r -> copy.retirements.add(new Retirement(r.getFromDate(), r.getToDate())));
        holds.forEach(h -> copy.holds.add(new Hold(h.getFromDate(), h.getToDate())));
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

    public long getPosition() {
        return position;
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

    /** The person's past retirements, earliest from-date first. */
    public List<Retirement> getRetirements() {
        return retirements.stream().sorted(Comparator.comparing(Retirement::getFromDate)
                .thenComparing(Retirement::getToDate)).toList();
    }

    /** Add a past retirement, keeping the others. */
    public void addRetirement(LocalDate from, LocalDate to) {
        retirements.add(new Retirement(from, to));
    }

    /** Unretire the person on a day: their retirement is kept as a past one, to that day. */
    public void unretire(LocalDate on) {
        retirements.add(new Retirement(retiredFrom, on));
        retiredFrom = null;
    }

    /** Whether a day falls after the from-date and before the to-date of a past retirement. */
    public boolean isWithinPastRetirement(LocalDate day) {
        return retirements.stream().anyMatch(r -> r.within(day));
    }

    /** The person's snapshots, oldest first. */
    public List<Snapshot> getSnapshots() {
        return List.copyOf(snapshots);
    }

    /** Add a snapshot after the others. */
    public void addSnapshot(LocalDate day, String dueTotal) {
        snapshots.add(new Snapshot(day, dueTotal));
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

    /** The sheet the person was transferred from; null when they were not transferred. */
    public String getOrigin() {
        return origin;
    }

    /** The person's contact, held by no one else on the sheet; null when they have none. */
    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    /** The person's tags as stored (see {@link Tags}); null when they have none. */
    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
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
