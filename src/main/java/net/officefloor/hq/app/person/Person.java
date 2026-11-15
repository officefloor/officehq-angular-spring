package net.officefloor.hq.app.person;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.officefloor.hq.app.history.HistoryListener;

/** A person on a sheet, with a name and a dated grade (A, B or C). */
@Entity
@EntityListeners(HistoryListener.class)
@Table(name = "person")
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    /** The first grade the person was given: in force from the beginning of time until a later one. */
    @Column(name = "grade", nullable = false)
    private String grade;

    /** The grades recorded since, by the date each is in force from. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    @MapKeyColumn(name = "from_date")
    @Column(name = "grade", nullable = false)
    private Map<LocalDate, String> datedGrades = new HashMap<>();

    /** The person's own terms in days, ahead of their grade's and the sheet's; null when they have none. */
    @Column(name = "terms")
    private Integer terms;

    /** The person's contact, trimmed and lower-cased; null when they have none. */
    @Column(name = "contact")
    private String contact;

    /** The person's aliases, kept trimmed: other names the person is found by. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_alias", joinColumns = @JoinColumn(name = "person_id"))
    @Column(name = "alias", nullable = false)
    private Set<String> aliases = new HashSet<>();

    /** The date the person is retired from; null while not retired. Set, they count as retired
     *  at once, whatever the date. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** The person's holds, in the order they were added: their OPEN claims dated within one count
     *  in no live total (unless bound by a statement). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    @OrderColumn(name = "position")
    private List<Hold> holds = new ArrayList<>();

    /** Raised on every save, so an edit of a version someone else has since saved is refused. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        this.name = name;
        this.grade = grade;
    }

    /** A copy of the person for another sheet, as a transfer carries them: their name, every grade
     *  they were given (and its from-date), their terms, contact and aliases. */
    public Person transferredTo(String sheetId) {
        Person copy = new Person(sheetId, name, grade);
        copy.datedGrades.putAll(datedGrades);
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

    /** A grade and the date it is in force from (null: from the beginning of time). */
    public record DatedGrade(String grade, LocalDate from) {
    }

    /** The grade in force on a date: the one with the latest from-date on or before it, else the first. */
    public String gradeOn(LocalDate on) {
        Map.Entry<LocalDate, String> latest = new TreeMap<>(datedGrades).floorEntry(on);
        return latest == null ? grade : latest.getValue();
    }

    /** Record a grade from a date, in place of any recorded from the same date. */
    public void recordGrade(String grade, LocalDate from) {
        datedGrades.put(from, grade);
    }

    /** Every grade the person has been given, by from-date, the first one (undated) leading. */
    public List<DatedGrade> getGrades() {
        List<DatedGrade> out = new ArrayList<>();
        out.add(new DatedGrade(grade, null));
        new TreeMap<>(datedGrades).forEach((from, g) -> out.add(new DatedGrade(g, from)));
        return out;
    }

    public Integer getTerms() {
        return terms;
    }

    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    /** The person's aliases, sorted as names are compared (ignoring case). */
    public List<String> getAliases() {
        return aliases.stream()
                .sorted(Comparator.comparing((String a) -> a.toLowerCase(Locale.ROOT)).thenComparing(a -> a))
                .toList();
    }

    public void addAlias(String alias) {
        aliases.add(alias);
    }

    /** Whether the name or one of the aliases equals {@code name}, as names are compared. */
    public boolean goesBy(String name) {
        return this.name.equalsIgnoreCase(name) || aliases.stream().anyMatch(a -> a.equalsIgnoreCase(name));
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

    /** Whether a claim dated {@code date} is refused for the person: one after their retirement date. */
    public boolean retiredBefore(LocalDate date) {
        return retiredFrom != null && date.isAfter(retiredFrom);
    }

    /** Whether the person's retirement date is reached on {@code today} (on or after it): their
     *  open claims, unless bound by a statement, then count in no live total. */
    public boolean retiredOn(LocalDate today) {
        return retiredFrom != null && !today.isBefore(retiredFrom);
    }

    /** A hold from a date to an optional one (null: open-ended), both inclusive. */
    @Embeddable
    public record Hold(@Column(name = "from_date", nullable = false) LocalDate from,
            @Column(name = "to_date") LocalDate to) {
        public boolean covers(LocalDate date) {
            return !date.isBefore(from) && (to == null || !date.isAfter(to));
        }
    }

    public List<Hold> getHolds() {
        return List.copyOf(holds);
    }

    public void addHold(LocalDate from, LocalDate to) {
        holds.add(new Hold(from, to));
    }

    /** Whether the person has any hold. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** Whether a hold of the person covers the date. */
    public boolean heldOn(LocalDate date) {
        return holds.stream().anyMatch(h -> h.covers(date));
    }

    public long getVersion() {
        return version;
    }
}
