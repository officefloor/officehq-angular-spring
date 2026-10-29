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
import java.util.Objects;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.ClaimStatus;

/**
 * A person on a sheet, with a name (unique on the sheet, ignoring case) and a dated grade (A, B or C): each grade
 * applies from its from-date, the first one given from the beginning of time; and the terms of their own, if any,
 * from the beginning of time and from their from-dates.
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

    /** The person's place in the sheet's list, smallest first. */
    @Column(name = "list_position", nullable = false)
    private long position;

    /** The person's own terms from the beginning of time; null: none. */
    @Column(name = "terms")
    private Integer terms;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_terms", joinColumns = @JoinColumn(name = "person_id"))
    private List<DatedTerms> datedTerms = new ArrayList<>();

    /** The person's contact, without surrounding spaces and lower-cased; null: none. */
    @Column(name = "contact")
    private String contact;

    /** The person's tags, sorted and comma separated; null for a person with none. */
    @Column(name = "tags")
    private String tags;

    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** The sheet the person was transferred from; null: not transferred. */
    @Column(name = "origin_sheet")
    private String originSheet;

    /** The sheet's today when the person arrived by transfer; null: not transferred, or before that was kept. */
    @Column(name = "arrived_on")
    private LocalDate arrivedOn;

    /** The user who transferred the person here; null: not transferred, or before that was kept. */
    @Column(name = "arrived_by")
    private String arrivedBy;

    /** The sheet's today when the person was added; null: before that was kept. */
    @Column(name = "added_on")
    private LocalDate addedOn;

    /** The sheet's today when the retirement date was set; null: before that was kept, or not retired. */
    @Column(name = "retired_on")
    private LocalDate retiredOn;

    /** The reason a steward flagged the person; null: never flagged. */
    @Column(name = "flag_reason")
    private String flagReason;

    /** The last day the person's flag is in force; null: never flagged. */
    @Column(name = "flag_until")
    private LocalDate flagUntil;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    private List<DatedHold> holds = new ArrayList<>();

    /** The retirements the person was unretired from. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_retirement", joinColumns = @JoinColumn(name = "person_id"))
    private List<PastRetirement> pastRetirements = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_alias", joinColumns = @JoinColumn(name = "person_id"))
    private List<PersonAlias> aliases = new ArrayList<>();

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
        then.position = position;
        then.originSheet = originSheet;
        then.terms = terms;
        datedTerms.stream().filter(t -> t.madeBy(day)).forEach(then.datedTerms::add);
        then.addedOn = addedOn;
        then.version = version;
        grades.stream().filter(g -> g.madeBy(day)).forEach(then.grades::add);
        if (then.grades.isEmpty()) {
            // the first grade they were given stands for them
            grades.stream().filter(g -> g.getFrom() == null).forEach(then.grades::add);
        }
        holds.stream().filter(h -> h.madeBy(day)).forEach(then.holds::add);
        then.aliases.addAll(aliases);
        if (retiredOn == null || !retiredOn.isAfter(day)) {
            then.retiredFrom = retiredFrom;
            then.retiredOn = retiredOn;
        }
        for (PastRetirement r : pastRetirements) {
            if (r.unretiredBy(day)) {
                then.pastRetirements.add(r);
            } else if (r.retiredBy(day)) {
                // not yet unretired at the end of the day: still retired then
                then.retiredFrom = r.getFrom();
                then.retiredOn = r.getRetiredOn();
            }
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

    public long getPosition() {
        return position;
    }

    public void setPosition(long position) {
        this.position = position;
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

    /**
     * The person's own terms from the beginning of time in business days: a claim of theirs is due by that many business
     * days after its date, unless dated terms are in force on it; null for none.
     */
    public Integer getTerms() {
        return terms;
    }

    /** Every dated terms recorded for the person, earliest from-date first. */
    public List<DatedTerms> getDatedTerms() {
        return datedTerms.stream().sorted(Comparator.comparing(DatedTerms::getFrom)).toList();
    }

    /**
     * The person's own terms in force on the date: those with the latest from-date on or before it, else those from
     * the beginning of time; null for none.
     */
    public Integer termsOn(LocalDate date) {
        DatedTerms inForce = datedTerms.stream().filter(t -> date != null && !t.getFrom().isAfter(date))
                .max(Comparator.comparing(DatedTerms::getFrom)).orElse(null);
        return inForce != null ? inForce.getTerms() : terms;
    }

    /** Records the terms (null: none) from the date, replacing those already recorded from that same date. */
    public void recordTerms(Integer terms, LocalDate from, LocalDate madeOn) {
        datedTerms.removeIf(t -> t.getFrom().equals(from));
        datedTerms.add(new DatedTerms(terms, from, madeOn));
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

    /** The date the person is retired from; null while they are not retired. */
    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    /** Retires the person from the date, set while the sheet's today was the made-on date. */
    public void setRetiredFrom(LocalDate retiredFrom, LocalDate madeOn) {
        this.retiredFrom = retiredFrom;
        this.retiredOn = madeOn;
    }

    /**
     * Unretires the person, while the sheet's today is the date: their retirement is kept as a past one, from its
     * from-date to the date, and they are no longer retired.
     */
    public void unretire(LocalDate on) {
        pastRetirements.add(new PastRetirement(retiredFrom, on, retiredOn, on));
        this.retiredFrom = null;
        this.retiredOn = null;
    }

    /** The retirements the person was unretired from, earliest from-date first. */
    public List<PastRetirement> getPastRetirements() {
        return pastRetirements.stream().sorted(Comparator.comparing(PastRetirement::getFrom)
                .thenComparing(PastRetirement::getTo)).toList();
    }

    /**
     * Whether a claim of the person's dated on the date is refused for a retirement: dated after their retirement
     * date, or after the from-date and before the to-date of a past retirement.
     */
    public boolean refusesClaimOn(LocalDate date) {
        return (retiredFrom != null && date != null && date.isAfter(retiredFrom))
                || pastRetirements.stream().anyMatch(r -> r.refuses(date));
    }

    /** A person with a retirement date set is retired at once, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** Whether the person's retirement date is reached on the day: the day is on or after it. */
    public boolean isRetiredOn(LocalDate day) {
        return retiredFrom != null && day != null && !day.isBefore(retiredFrom);
    }

    /** The person's aliases, sorted as names are compared: ignoring case. */
    public List<String> getAliases() {
        return aliases.stream().map(PersonAlias::getAlias)
                .sorted(Comparator.comparing((String a) -> a.toLowerCase(Locale.ROOT)).thenComparing(a -> a))
                .toList();
    }

    /** The sheet the person was transferred from; null: not transferred. */
    public String getOriginSheet() {
        return originSheet;
    }

    /** Flags the person with the reason until the date, replacing any flag before it. */
    public void flag(String reason, LocalDate until) {
        this.flagReason = reason;
        this.flagUntil = until;
    }

    /** The reason of the person's flag while it is in force on the day (on or before its until-date); else null. */
    public String flagOn(LocalDate day) {
        return flagReason != null && flagUntil != null && !day.isAfter(flagUntil) ? flagReason : null;
    }

    /** The sheet's today when the person arrived by transfer; null: not transferred, or before that was kept. */
    public LocalDate getArrivedOn() {
        return arrivedOn;
    }

    /** The user who transferred the person here; null: not transferred, or before that was kept. */
    public String getArrivedBy() {
        return arrivedBy;
    }

    /**
     * Moves the person, with their grades, holds and aliases, to the other sheet; they came from this one, transferred
     * by the user while the other sheet's today was the date.
     */
    public void moveToSheet(String sheetId, LocalDate arrivedOn, String arrivedBy) {
        this.originSheet = this.sheetId;
        this.sheetId = sheetId;
        this.arrivedOn = arrivedOn;
        this.arrivedBy = arrivedBy;
        List<PersonAlias> moved = aliases.stream().map(a -> new PersonAlias(sheetId, a.getAlias())).toList();
        aliases.clear();
        aliases.addAll(moved);
    }

    /** Gives the person the alias, without surrounding spaces; the ones already given are kept. */
    public void addAlias(String alias) {
        aliases.add(new PersonAlias(sheetId, alias));
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
     * held, or within a hold, that no statement binds, or once the person's retirement date is reached on the sheet's today, and
     * one deferred on the sheet's today.
     */
    public boolean countsLive(Claim claim, LocalDate today) {
        return claim.getStatus().counts() && !claim.isDeferredOn(today)
                && !(claim.getStatus().isOpen() && !claim.isBound()
                && (claim.isHeld() || holds.stream().anyMatch(h -> h.covers(claim.getDate())) || isRetiredOn(today)));
    }

    /**
     * Whether the claim counts toward the person's figure for a period ending on the day: one whose status at the end
     * of that day counts, left out as the live rules say when deferred on the sheet's today or held.
     */
    public boolean countsAtEndOf(Claim claim, LocalDate end, LocalDate today) {
        ClaimStatus then = claim.statusAtEndOf(end);
        return then != null && then.counts() && !claim.isDeferredOn(today)
                && !(claim.getStatus().isOpen() && !claim.isBound()
                && (claim.isHeld() || holds.stream().anyMatch(h -> h.covers(claim.getDate())) || isRetiredOn(today)));
    }

    /** The version of the person as stored, raised on each save. */
    public long getVersion() {
        return version;
    }
}
