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
import java.math.BigDecimal;
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
import org.hibernate.annotations.OptimisticLock;

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

    /** The person's own terms in days from the beginning of time, ahead of their grade's and the
     *  sheet's; null when they have none. */
    @Column(name = "terms")
    private Integer terms;

    /** The terms recorded since, by the date each is in force from ({@link #NO_TERMS}: none). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_terms", joinColumns = @JoinColumn(name = "person_id"))
    @MapKeyColumn(name = "from_date")
    @Column(name = "terms", nullable = false)
    private Map<LocalDate, Integer> datedTerms = new HashMap<>();

    /** The person's contact, trimmed and lower-cased; null when they have none. */
    @Column(name = "contact")
    private String contact;

    /** The person's tags: lower-case, sorted and comma separated, without repeats; null when they
     *  have none. A claim counts for a tag its person carries as well as for its own. */
    @Column(name = "tags")
    private String tags;

    /** The person's aliases, kept trimmed: other names the person is found by. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_alias", joinColumns = @JoinColumn(name = "person_id"))
    @Column(name = "alias", nullable = false)
    private Set<String> aliases = new HashSet<>();

    /** The date the person is retired from; null while not retired. Set, they count as retired
     *  at once, whatever the date. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** The person's past retirements, in the order they were unretired: no claim dated after the
     *  from-date and before the to-date of one can be added for them. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_retirement", joinColumns = @JoinColumn(name = "person_id"))
    @OrderColumn(name = "position")
    private List<Retirement> retirements = new ArrayList<>();

    /** The person's holds, in the order they were added: their OPEN claims dated within one count
     *  in no live total (unless bound by a statement). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    @OrderColumn(name = "position")
    private List<Hold> holds = new ArrayList<>();

    /** The person's snapshot lines, oldest first: one written each time the sheet's today moves.
     *  Taking one is no edit of the person, so it leaves their version as it is. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_snapshot", joinColumns = @JoinColumn(name = "person_id"))
    @OrderColumn(name = "position")
    @OptimisticLock(excluded = true)
    private List<Snapshot> snapshots = new ArrayList<>();

    /** The person's place in the sheet's list: people are listed by it, lowest first. Moving a
     *  person is no edit of them, so it leaves their version as it is. */
    @Column(name = "list_position", nullable = false)
    @OptimisticLock(excluded = true)
    private long position;

    /** The id of the sheet the person was transferred from; null for anyone not transferred here. */
    @Column(name = "origin_sheet_id")
    private String originSheetId;

    /** The sheet's today on which a transfer brought the person here; null for anyone not
     *  transferred here. */
    @Column(name = "arrived_on")
    private LocalDate arrivedOn;

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
     *  they were given (and its from-date), their terms, contact, tags and aliases. */
    public Person transferredTo(String sheetId) {
        Person copy = new Person(sheetId, name, grade);
        copy.datedGrades.putAll(datedGrades);
        copy.terms = terms;
        copy.datedTerms.putAll(datedTerms);
        copy.contact = contact;
        copy.tags = tags;
        copy.aliases.addAll(aliases);
        copy.originSheetId = this.sheetId;
        return copy;
    }

    /** A copy of the person for a clone of the sheet: their name, every grade they were given (and
     *  its from-date), their terms, contact, tags, aliases, holds, retirement (and past ones), place in the
     *  list and the sheet they came from. */
    public Person clonedTo(String sheetId) {
        Person copy = new Person(sheetId, name, grade);
        copy.datedGrades.putAll(datedGrades);
        copy.terms = terms;
        copy.datedTerms.putAll(datedTerms);
        copy.contact = contact;
        copy.tags = tags;
        copy.aliases.addAll(aliases);
        copy.retiredFrom = retiredFrom;
        copy.retirements.addAll(retirements);
        copy.holds.addAll(holds);
        copy.position = position;
        copy.originSheetId = originSheetId;
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

    /** The value of dated terms recording that the person has none of their own from that date. */
    public static final int NO_TERMS = -1;

    /** Terms and the date they are in force from (null terms: none of their own). */
    public record DatedTerms(Integer terms, LocalDate from) {
    }

    /** The person's own terms from the beginning of time, until any later ones; null when none. */
    public Integer getTerms() {
        return terms;
    }

    /** The person's own terms in force on a date: those with the latest from-date on or before it,
     *  else those from the beginning of time; null when they have none then. */
    public Integer termsOn(LocalDate on) {
        Map.Entry<LocalDate, Integer> latest = new TreeMap<>(datedTerms).floorEntry(on);
        if (latest == null) {
            return terms;
        }
        return latest.getValue() == NO_TERMS ? null : latest.getValue();
    }

    /** Record terms (null: none) from a date, in place of any recorded from the same date; answers
     *  whether that changed what is recorded. */
    public boolean recordTerms(Integer terms, LocalDate from) {
        Integer value = terms == null ? NO_TERMS : terms;
        return !value.equals(datedTerms.put(from, value));
    }

    /** The terms recorded since the beginning of time, by from-date. */
    public List<DatedTerms> getDatedTerms() {
        List<DatedTerms> out = new ArrayList<>();
        new TreeMap<>(datedTerms).forEach((from, t) -> out.add(new DatedTerms(t == NO_TERMS ? null : t, from)));
        return out;
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

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
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

    /** Whether a claim dated {@code date} is refused for the person: one after their retirement
     *  date, or after the from-date and before the to-date of a past retirement. */
    public boolean retiredBefore(LocalDate date) {
        return (retiredFrom != null && date.isAfter(retiredFrom))
                || retirements.stream().anyMatch(r -> r.covers(date));
    }

    /** A past retirement: from a date to the day the person was unretired. */
    @Embeddable
    public record Retirement(@Column(name = "from_date", nullable = false) LocalDate from,
            @Column(name = "to_date", nullable = false) LocalDate to) {
        /** Whether a claim dated {@code date} falls within it: after the from and before the to. */
        public boolean covers(LocalDate date) {
            return date.isAfter(from) && date.isBefore(to);
        }
    }

    /** A snapshot line: the day being left and the person's due-total on it (null: n/a). */
    @Embeddable
    public record Snapshot(@Column(name = "snapshot_day", nullable = false) LocalDate day,
            @Column(name = "due_total") BigDecimal dueTotal) {
    }

    public List<Snapshot> getSnapshots() {
        return List.copyOf(snapshots);
    }

    public void addSnapshot(LocalDate day, BigDecimal dueTotal) {
        snapshots.add(new Snapshot(day, dueTotal));
    }

    public List<Retirement> getRetirements() {
        return List.copyOf(retirements);
    }

    public void addRetirement(LocalDate from, LocalDate to) {
        retirements.add(new Retirement(from, to));
    }

    /** Unretire the person on a date: their retirement is kept as a past one, to that date. */
    public void unretire(LocalDate on) {
        retirements.add(new Retirement(retiredFrom, on));
        retiredFrom = null;
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

    public LocalDate getArrivedOn() {
        return arrivedOn;
    }

    public void setArrivedOn(LocalDate arrivedOn) {
        this.arrivedOn = arrivedOn;
    }

    public String getOriginSheetId() {
        return originSheetId;
    }

    public void setOriginSheetId(String originSheetId) {
        this.originSheetId = originSheetId;
    }

    public long getPosition() {
        return position;
    }

    public void setPosition(long position) {
        this.position = position;
    }

    public long getVersion() {
        return version;
    }
}
