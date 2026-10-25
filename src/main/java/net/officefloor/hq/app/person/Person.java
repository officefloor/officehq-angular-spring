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

    /** Every hold put on the person; their open claims dated within one, unless bound by a statement,
     *  count in no live total. */
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

    /** The person's contact, trimmed and lower-cased; null when they have none. */
    @Column(name = "contact")
    private String contact;

    /** The person's place in the list, lowest first; null until they are moved, when their id is their place. */
    @Column(name = "position")
    private Long position;

    /** The sheet the person was transferred from; null when they were added on this sheet. */
    @Column(name = "origin_sheet_id")
    private String originSheetId;

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
    public Person copyTo(String otherSheetId) {
        Person copy = new Person();
        copy.sheetId = otherSheetId;
        copy.originSheetId = sheetId;
        copy.addedOn = addedOn;
        copy.setName(name);
        for (PersonGrade g : getGrades()) copy.grades.add(new PersonGrade(g.getGrade(), g.getFrom(), g.getMadeOn()));
        copy.terms = terms;
        copy.contact = contact;
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
        copy.contact = contact;
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
     *  keeping every other one. */
    public void setGrade(Grade grade, LocalDate from, LocalDate madeOn) {
        for (PersonGrade g : grades) {
            if (from.equals(g.getFrom())) {
                if (g.getGrade() != grade) g.setGrade(grade, madeOn);
                return;
            }
        }
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

    /** Whether the person's unbound open claim of this date leaves the live totals: dated within one of
     *  their holds, or the person's retirement date is reached by the sheet's today. */
    public boolean isLeftOut(LocalDate date, LocalDate today) {
        return isHeldOn(date) || isRetiredBy(today);
    }

    /** Whether the person's unbound open claim of this date left the totals at the end of another date
     *  (asOf), which stands in for the sheet's today: dated within one of the holds put on them while the
     *  sheet's today was on or before it, or their retirement then made reached by it. */
    public boolean isLeftOutAsOf(LocalDate date, LocalDate asOf) {
        boolean held = holds.stream().anyMatch(h -> h.madeBy(asOf) && h.covers(date));
        boolean retired = retiredFrom != null && (retiredMadeOn == null || !retiredMadeOn.isAfter(asOf))
                && !asOf.isBefore(retiredFrom);
        return held || retired || retirements.stream().anyMatch(r -> r.inForceAsOf(asOf));
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

    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    public String getContact() {
        return contact;
    }

    /** Stores the contact trimmed and lower-cased; a blank contact is none. */
    public void setContact(String contact) {
        this.contact = contactOf(contact);
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

    /** The sheet the person was transferred from, or null when they were added on this sheet. */
    public String getOriginSheetId() {
        return originSheetId;
    }

    public long getVersion() {
        return version;
    }
}
