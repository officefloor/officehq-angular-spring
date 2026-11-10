package net.officefloor.hq.app.person;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;

/** A person on a sheet: a name and their grades (A, B or C), each from a from-date, and the date they are retired from, if any;
 * and the holds they have had, each over a span of dates. */
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

    /** The grades recorded, the first from the beginning of time (no from-date), the others each from a date. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    private List<DatedGrade> grades = new ArrayList<>();

    /** The person's own terms in days from the beginning of time, or null when they have none. */
    @Column(name = "terms")
    private Integer terms;

    /** The person's own terms recorded from a from-date, each in force until their next. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_terms", joinColumns = @JoinColumn(name = "person_id"))
    private List<DatedTerms> datedTerms = new ArrayList<>();

    /** The person's contact, trimmed and lower-cased, or null when they have none. */
    @Column(name = "contact")
    private String contact;

    /** The person's tags: trimmed, lower-cased, without repeats, sorted and comma separated; none when they have none. */
    @Column(name = "tags")
    private String tags;

    /** Set once retired: the person counts as retired at once, whatever the date. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** The sheet's today when the person was retired; none while not retired. */
    @Column(name = "retired_on")
    private LocalDate retiredOn;

    /** The person's past retirements, each ended by unretiring them. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_retirement", joinColumns = @JoinColumn(name = "person_id"))
    private List<PastRetirement> pastRetirements = new ArrayList<>();

    /** The person's snapshot lines, oldest first; written by the sheet when its today moves. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_snapshot", joinColumns = @JoinColumn(name = "person_id"))
    @OrderBy("pos")
    private List<PersonSnapshot> snapshots = new ArrayList<>();

    /** The sheet's today when the person was added; none for one added before it was recorded. */
    @Column(name = "added_on")
    private LocalDate addedOn;

    /** The person's OPEN claims dated within a hold, and not bound by a statement, count in no live total. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    private List<DatedHold> holds = new ArrayList<>();

    /** The person's aliases, trimmed; each shares the names' uniqueness on the sheet. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_alias", joinColumns = @JoinColumn(name = "person_id"))
    @Column(name = "alias", nullable = false)
    private Set<String> aliases = new HashSet<>();

    /** The person's place in the sheet's list, lowest first. */
    @Column(name = "position", nullable = false)
    private long position;

    /** The sheet the person was transferred from; null when they were not transferred in. */
    @Column(name = "origin_sheet")
    private String originSheet;

    /** The sheet's today when the person was transferred in; null when they were not transferred in. */
    @Column(name = "arrived_on")
    private LocalDate arrivedOn;

    /** Bumped on every save; an edit must carry the version it was made from. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade, LocalDate addedOn) {
        this.sheetId = sheetId;
        this.name = name;
        this.addedOn = addedOn;
        this.grades.add(new DatedGrade(null, grade, addedOn));
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    /**
     * The person as they arrive on another sheet: their name, every grade they were given (each with
     * its from-date), their terms, contact, tags and aliases; not their holds or retirement. They carry
     * this sheet as the one they came from, and the other sheet's today as the day they arrived.
     */
    public Person transferredTo(String toSheetId, LocalDate arrivedOn) {
        Person copy = new Person();
        copy.sheetId = toSheetId;
        copy.name = name;
        copy.addedOn = addedOn;
        grades.forEach(g -> copy.grades.add(new DatedGrade(g.getFromDate(), g.getGrade(), g.getMadeOn())));
        copy.terms = terms;
        datedTerms.forEach(t -> copy.datedTerms.add(new DatedTerms(t.getFromDate(), t.getTerms())));
        copy.contact = contact;
        copy.tags = tags;
        copy.aliases.addAll(aliases);
        copy.originSheet = sheetId;
        copy.arrivedOn = arrivedOn;
        return copy;
    }

    public String getOriginSheet() {
        return originSheet;
    }

    public LocalDate getArrivedOn() {
        return arrivedOn;
    }

    public long getPosition() {
        return position;
    }

    public void setPosition(long position) {
        this.position = position;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** The grades recorded, the one from the beginning of time first and then by from-date. */
    public List<DatedGrade> getGrades() {
        return grades.stream().sorted(Comparator.comparing(DatedGrade::getFromDate,
                Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
    }

    /** The grade in force on a date: the one with the latest from-date on or before it. */
    public String gradeOn(LocalDate date) {
        String found = null;
        for (DatedGrade g : getGrades()) {
            if (g.getFromDate() == null || !g.getFromDate().isAfter(date)) {
                found = g.getGrade();
            }
        }
        return found;
    }

    /**
     * The grade in force on a date as the person stood at the end of a day: the one with the latest
     * from-date on or before the date, of those recorded while the sheet's today was on or before the day.
     */
    public String gradeOn(LocalDate date, LocalDate asOf) {
        String found = null;
        for (DatedGrade g : getGrades()) {
            if (g.madeBy(asOf) && (g.getFromDate() == null || !g.getFromDate().isAfter(date))) {
                found = g.getGrade();
            }
        }
        return found;
    }

    /**
     * Record the grade from a date, replacing any grade already recorded from that same date;
     * {@code madeOn} is the sheet's today.
     */
    public void setGradeFrom(LocalDate fromDate, String grade, LocalDate madeOn) {
        grades.removeIf(g -> fromDate.equals(g.getFromDate()));
        grades.add(new DatedGrade(fromDate, grade, madeOn));
    }

    /** The person's own terms from the beginning of time, or null when they have none. */
    public Integer getTerms() {
        return terms;
    }

    /** Record the person's own terms (null: none) from the beginning of time. */
    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    /** The terms recorded from a from-date, by from-date. */
    public List<DatedTerms> getDatedTerms() {
        return datedTerms.stream().sorted(Comparator.comparing(DatedTerms::getFromDate)).toList();
    }

    /** Record the person's own terms (null: none) from a date, replacing any recorded from that same date. */
    public void setTermsFrom(LocalDate fromDate, Integer terms) {
        datedTerms.removeIf(t -> fromDate.equals(t.getFromDate()));
        datedTerms.add(new DatedTerms(fromDate, terms));
    }

    /**
     * The person's own terms in force on a date: those with the latest from-date on or before it,
     * else those from the beginning of time; null when none are in force.
     */
    public Integer termsOn(LocalDate date) {
        Integer found = terms;
        for (DatedTerms t : getDatedTerms()) {
            if (!t.getFromDate().isAfter(date)) {
                found = t.getTerms();
            }
        }
        return found;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    /** The person's tags, sorted; empty when they have none. */
    public List<String> getTags() {
        return tags == null ? List.of() : List.of(tags.split(","));
    }

    /** Give the person tags, already trimmed, lower-cased, without repeats and sorted. */
    public void setTags(List<String> tags) {
        this.tags = tags.isEmpty() ? null : String.join(",", tags);
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    /** Retire the person from a date; {@code madeOn} is the sheet's today. */
    public void setRetiredFrom(LocalDate retiredFrom, LocalDate madeOn) {
        this.retiredFrom = retiredFrom;
        this.retiredOn = madeOn;
    }

    /**
     * Unretire the person on a day (the sheet's today): they are no longer retired, and the
     * retirement is kept as a past one from its from-date to that day.
     */
    public void unretire(LocalDate on) {
        pastRetirements.add(new PastRetirement(retiredFrom, on, retiredOn));
        this.retiredFrom = null;
        this.retiredOn = null;
    }

    /** The snapshot lines, oldest first. */
    public List<PersonSnapshot> getSnapshots() {
        return List.copyOf(snapshots);
    }

    /** The past retirements, by from-date and then to-date. */
    public List<PastRetirement> getPastRetirements() {
        return pastRetirements.stream().sorted(Comparator.comparing(PastRetirement::getFromDate)
                .thenComparing(PastRetirement::getToDate)).toList();
    }

    /**
     * Whether a claim can be dated on a day: not after the retirement date while retired, nor after
     * the from-date and before the to-date of a past retirement.
     */
    public boolean refusesClaimOn(LocalDate date) {
        return (retiredFrom != null && date.isAfter(retiredFrom))
                || pastRetirements.stream().anyMatch(r -> r.excludes(date));
    }

    /** Whether the person had been added by the end of a day: the sheet's today then on or before it. */
    public boolean addedBy(LocalDate day) {
        return addedOn == null || !addedOn.isAfter(day);
    }

    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** Whether the retirement date is reached on a day (the day on or after it). */
    public boolean retiredOn(LocalDate date) {
        return retiredFrom != null && date != null && !date.isBefore(retiredFrom);
    }

    /** The holds recorded, by from-date. */
    /** The person's aliases, sorted as names are compared: without regard to case. */
    public List<String> getAliases() {
        return aliases.stream().sorted(Comparator.comparing((String a) -> a.toLowerCase(Locale.ROOT))
                .thenComparing(Comparator.naturalOrder())).toList();
    }

    public void addAlias(String alias) {
        aliases.add(alias);
    }

    public List<DatedHold> getHolds() {
        return holds.stream().sorted(Comparator.comparing(DatedHold::getFromDate)
                .thenComparing(DatedHold::getToDate, Comparator.nullsLast(Comparator.naturalOrder()))).toList();
    }

    /** Add a hold; {@code madeOn} is the sheet's today. */
    public void addHold(LocalDate fromDate, LocalDate toDate, LocalDate madeOn) {
        holds.add(new DatedHold(fromDate, toDate, madeOn));
    }

    /** Whether the person has any hold. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** Whether a day lies within one of the person's holds. */
    public boolean heldOn(LocalDate date) {
        return holds.stream().anyMatch(h -> h.covers(date));
    }

    /**
     * Whether a claim of this person counts toward the live totals: an open claim dated within a
     * hold does not, nor does one of a person whose retirement date the sheet's today has reached,
     * unless a statement binds it; nor does a claim deferred on the sheet's today.
     */
    public boolean counts(Claim claim, LocalDate today) {
        return counts(claim, claim.getStatus(), today);
    }

    /**
     * Whether a claim of this person counts by the same rules, had it the given status; a hold or a
     * reached retirement takes out only a claim that is open now as well (one since settled or voided is not).
     */
    public boolean counts(Claim claim, ClaimStatus status, LocalDate today) {
        return status.counts() && !claim.isDeferredOn(status, today) && (claim.isStated()
                || !(status.isOpen() && claim.getStatus().isOpen() && (heldOn(claim.getDate()) || retiredOn(today))));
    }

    /**
     * Whether a claim of this person counted at the end of a day, as the person stood then: had it
     * the given status then, and was it bound by a statement then ({@code stated}). The day stands in
     * for the sheet's today, and only holds and a retirement recorded while the sheet's today was on
     * or before it count.
     */
    public boolean countedAsOf(Claim claim, ClaimStatus status, LocalDate day, boolean stated) {
        boolean held = holds.stream().anyMatch(h -> h.madeBy(day) && h.covers(claim.getDate()));
        boolean retired = (retiredFrom != null && (retiredOn == null || !retiredOn.isAfter(day))
                && !day.isBefore(retiredFrom)) || pastRetirements.stream().anyMatch(r -> r.retiredAsOf(day));
        return status.counts() && !claim.isDeferredAsOf(status, day)
                && (stated || !(status.isOpen() && (held || retired)));
    }

    public long getVersion() {
        return version;
    }
}
