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

    /** The sheet's today when the person was last retired; null when never, or before this was kept. */
    @Column(name = "retired_made_on")
    private LocalDate retiredMadeOn;

    /** Every past retirement of the person, ended when they were unretired. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private Set<PersonRetirement> retirements = new LinkedHashSet<>();

    /** The sheet's today when the person was added; null when added before this was kept. */
    @Column(name = "added_on")
    private LocalDate addedOn;

    /** Every hold put on the person; a person with a hold in force gets no cut of a pool, their share
     *  spread over the others. Their claims still count in every total. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private Set<PersonHold> holds = new LinkedHashSet<>();

    /** The other names the person is found by. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private Set<PersonAlias> aliases = new LinkedHashSet<>();

    /** The person's own terms, in days; null when they have none and use their grade's, else the sheet's. */
    @Column(name = "terms")
    private Integer terms;

    /** The person's own terms recorded from a from-date, each in force until the next. */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "person_id", nullable = false)
    private Set<PersonTerms> datedTerms = new LinkedHashSet<>();

    /** The person's contact, trimmed and lower-cased; null when they have none. */
    @Column(name = "contact")
    private String contact;

    /** The person's tags, lower-cased, sorted and comma separated; none when they have none. */
    @Column(name = "tags")
    private String tags;

    /** The person's place in the list, lowest first; null until they are moved, when their id is their place. */
    @Column(name = "position")
    private Long position;

    /** The sheet the person was transferred from; null when they were added on this sheet. */
    @Column(name = "origin_sheet_id")
    private String originSheetId;

    /** The sheet's today when a transfer brought the person here; null when they did not arrive by transfer. */
    @Column(name = "arrived_on")
    private LocalDate arrivedOn;

    /** The reason of the steward's flag on the person; null when never flagged. */
    @Column(name = "flag_reason")
    private String flagReason;

    /** The last day the flag is in force; null when never flagged. */
    @Column(name = "flag_until")
    private LocalDate flagUntil;

    /** The user who may approve or reject the person's PENDING claims as a steward may; null when none. */
    @Column(name = "approver")
    private String approver;

    /** Bumped on each save; an edit made from an older version is refused. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    /** A person added to the sheet while its today is the date given (addedOn). */
    public Person(String sheetId, String name, Grade grade, LocalDate addedOn) {
        this.sheetId = sheetId;
        this.addedOn = addedOn;
        setName(name);
        grades.add(new PersonGrade(grade, null, addedOn));
    }

    /** A copy of the person for another sheet: their name, every grade they were given (each with its
     *  from-date), terms, contact and aliases; not their holds, nor their retirement. The copy came
     *  from this person's sheet. */
    public Person copyTo(String otherSheetId, LocalDate arrivedOn) {
        Person copy = new Person();
        copy.sheetId = otherSheetId;
        copy.originSheetId = sheetId;
        copy.arrivedOn = arrivedOn;
        copy.addedOn = addedOn;
        copy.setName(name);
        for (PersonGrade g : getGrades()) copy.grades.add(new PersonGrade(g.getGrade(), g.getFrom(), g.getMadeOn()));
        copy.terms = terms;
        for (PersonTerms t : datedTerms) copy.datedTerms.add(new PersonTerms(t.getTerms(), t.getFrom()));
        copy.contact = contact;
        copy.tags = tags;
        getAliases().forEach(copy::addAlias);
        return copy;
    }

    /** A clone of the person for another sheet: everything the person has (their name, every grade with
     *  its from-date, terms, contact, aliases, holds, retirement and past retirements, the sheet they came
     *  from). */
    public Person cloneTo(String otherSheetId) {
        Person copy = new Person();
        copy.sheetId = otherSheetId;
        copy.originSheetId = originSheetId;
        copy.addedOn = addedOn;
        copy.setName(name);
        for (PersonGrade g : getGrades()) copy.grades.add(new PersonGrade(g.getGrade(), g.getFrom(), g.getMadeOn()));
        copy.retiredFrom = retiredFrom;
        copy.retiredMadeOn = retiredMadeOn;
        for (PersonRetirement r : getRetirements()) {
            copy.retirements.add(new PersonRetirement(r.getFrom(), r.getTo(), r.getMadeOn()));
        }
        for (PersonHold h : getHolds()) copy.holds.add(new PersonHold(h.getFrom(), h.getTo(), h.getMadeOn()));
        copy.terms = terms;
        for (PersonTerms t : datedTerms) copy.datedTerms.add(new PersonTerms(t.getTerms(), t.getFrom()));
        copy.contact = contact;
        copy.tags = tags;
        copy.flagReason = flagReason;
        copy.flagUntil = flagUntil;
        copy.approver = approver;
        getAliases().forEach(copy::addAlias);
        return copy;
    }

    public LocalDate getAddedOn() {
        return addedOn;
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
        return gradeOn(date, null);
    }

    /** The grade in force on a date as the person's grades stood at the end of another date (asOf; null:
     *  as they stand now): only the grades given while the sheet's today was on or before it count. */
    public Grade gradeOn(LocalDate date, LocalDate asOf) {
        PersonGrade inForce = null;
        for (PersonGrade g : grades) {
            if (asOf != null && !g.madeBy(asOf)) continue;
            if (g.appliesBy(date) && (inForce == null || g.startsAfter(inForce))) inForce = g;
        }
        return inForce == null ? null : inForce.getGrade();
    }

    /** Give the person a grade from a date, replacing the grade they had from that same date and
     *  keeping every other one. A grade already in force on that date adds nothing, so an edit that
     *  leaves the grade as it is does not hide a grade given later from an earlier date. */
    public void setGrade(Grade grade, LocalDate from, LocalDate madeOn) {
        setGrade(grade, from, madeOn, false);
    }

    /** Give the person a grade from a date as {@link #setGrade(Grade, LocalDate, LocalDate)}; given
     *  explicitly, a grade already in force on that date is still recorded from it, so a grade given
     *  later from an earlier date does not reach past it. */
    public void setGrade(Grade grade, LocalDate from, LocalDate madeOn, boolean explicit) {
        for (PersonGrade g : grades) {
            if (from.equals(g.getFrom())) {
                if (g.getGrade() != grade) g.setGrade(grade, madeOn);
                return;
            }
        }
        if (!explicit && gradeOn(from) == grade) return;
        grades.add(new PersonGrade(grade, from, madeOn));
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    /** Retire the person from a date, while the sheet's today is the date given (madeOn). */
    public void setRetiredFrom(LocalDate retiredFrom, LocalDate madeOn) {
        this.retiredFrom = retiredFrom;
        this.retiredMadeOn = madeOn;
    }

    /** Whether the person is retired: true as soon as a retirement date is set, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }

    /** Unretire the person on a date (the sheet's today), keeping the retirement as a past one. */
    public void unretire(LocalDate on) {
        retirements.add(new PersonRetirement(retiredFrom, on, retiredMadeOn));
        this.retiredFrom = null;
        this.retiredMadeOn = null;
    }

    /** The person's past retirements, in the order they were ended. */
    public List<PersonRetirement> getRetirements() {
        List<PersonRetirement> ordered = new ArrayList<>(retirements);
        ordered.sort(Comparator.comparing(PersonRetirement::getTo)
                .thenComparing(PersonRetirement::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        return ordered;
    }

    /** Whether a claim of this date is refused: one dated after the person's retirement date, or after the
     *  from-date and before the to-date of a past retirement. */
    public boolean isRetiredOn(LocalDate date) {
        return (retiredFrom != null && date.isAfter(retiredFrom)) || retirements.stream().anyMatch(r -> r.refuses(date));
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

    /** Whether the person's retirement date is reached by the sheet's today (on or after it). */
    public boolean isRetiredBy(LocalDate today) {
        return retiredFrom != null && today != null && !today.isBefore(retiredFrom);
    }

    /** Whether the person's unbound open claims left the totals at the end of a date (asOf), which stands
     *  in for the sheet's today: their retirement then made reached by it. A hold leaves no claim out. */
    public boolean isRetiredAsOf(LocalDate asOf) {
        boolean retired = retiredFrom != null && (retiredMadeOn == null || !retiredMadeOn.isAfter(asOf))
                && !asOf.isBefore(retiredFrom);
        return retired || retirements.stream().anyMatch(r -> r.inForceAsOf(asOf));
    }

    /** Put a hold on the person from a date to a date (none: no end), while the sheet's today is the date
     *  given (madeOn). */
    public void hold(LocalDate from, LocalDate to, LocalDate madeOn) {
        holds.add(new PersonHold(from, to, madeOn));
    }

    /** The person's aliases, sorted as names are compared. */
    public List<String> getAliases() {
        return aliases.stream().sorted(Comparator.comparing(PersonAlias::getAliasKey))
                .map(PersonAlias::getAlias).toList();
    }

    /** Give the person another name they are found by, stored without its surrounding spaces. */
    public void addAlias(String alias) {
        aliases.add(new PersonAlias(sheetId, alias));
    }

    public Integer getTerms() {
        return terms;
    }

    /** Record the person's own terms (null: none) from the beginning of time, keeping their dated terms. */
    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    /** Record the person's own terms (null: none) from a date, replacing those recorded from that same
     *  date and keeping every other. */
    public void setTerms(Integer terms, LocalDate from) {
        for (PersonTerms t : datedTerms) {
            if (from.equals(t.getFrom())) {
                t.setTerms(terms);
                return;
            }
        }
        datedTerms.add(new PersonTerms(terms, from));
    }

    /** The person's own terms in force on a date: those with the latest from-date on or before it, else
     *  those from the beginning of time; null when none are in force. */
    public Integer termsOn(LocalDate date) {
        PersonTerms inForce = null;
        for (PersonTerms t : datedTerms) {
            if (!t.getFrom().isAfter(date) && (inForce == null || t.getFrom().isAfter(inForce.getFrom()))) inForce = t;
        }
        return inForce != null ? inForce.getTerms() : terms;
    }

    public String getContact() {
        return contact;
    }

    /** Stores the contact trimmed and lower-cased; a blank contact is none. */
    public void setContact(String contact) {
        this.contact = contactOf(contact);
    }

    /** The person's tags, sorted; empty when they have none. */
    public List<String> getTags() {
        return tags == null ? List.of() : List.of(tags.split(","));
    }

    /** Give the person their tags, each lower-cased and sorted, or (empty) none. */
    public void setTags(List<String> tags) {
        this.tags = tags.isEmpty() ? null : String.join(",", tags);
    }

    /** A contact as stored: without surrounding spaces, lower-cased; null when blank or none. */
    public static String contactOf(String contact) {
        return contact == null || contact.isBlank() ? null : contact.trim().toLowerCase(Locale.ROOT);
    }

    /** The person's place in the list, lowest first. */
    public long getPosition() {
        return position != null ? position : id;
    }

    /** Put the person at a place in the list. */
    public void placeAt(long position) {
        this.position = position;
    }

    /** The sheet's today when a transfer brought the person here, or null when they did not arrive by transfer. */
    public LocalDate getArrivedOn() {
        return arrivedOn;
    }

    /** The sheet the person was transferred from, or null when they were added on this sheet. */
    public String getOriginSheetId() {
        return originSheetId;
    }

    /** Flag the person with a reason until a date, replacing any earlier flag. */
    public void flag(String reason, LocalDate until) {
        this.flagReason = reason.trim();
        this.flagUntil = until;
    }

    public String getFlagReason() {
        return flagReason;
    }

    /** Whether the person's flag is in force on the sheet's today: on or before its until-date. */
    public boolean isFlaggedOn(LocalDate today) {
        return flagReason != null && flagUntil != null && today != null && !today.isAfter(flagUntil);
    }

    /** The flag's reason while it is in force on the sheet's today; null otherwise. */
    public String flagOn(LocalDate today) {
        return isFlaggedOn(today) ? flagReason : null;
    }

    public long getVersion() {
        return version;
    }

    public String getApprover() {
        return approver;
    }

    /** Name the user who may approve or reject the person's PENDING claims; blank names none. */
    public void setApprover(String approver) {
        this.approver = approver == null || approver.isBlank() ? null : approver.trim();
    }
}
