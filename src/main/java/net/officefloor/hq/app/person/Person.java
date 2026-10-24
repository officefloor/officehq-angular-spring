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

    /** The person's past retirements, each ended by an unretire. Loaded by a sub-select, so it loads
     * eagerly beside the grades. */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "person_retirement", joinColumns = @JoinColumn(name = "person_id"))
    private List<PersonRetirement> retirements = new ArrayList<>();

    /** The holds the person has been put on: their OPEN and PENDING claims dated within one count in no
     * live total, unless a statement binds them. The same hold may be put on more than once. Loaded by
     * a sub-select, so it loads eagerly beside the grades. */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    private List<PersonHold> holds = new ArrayList<>();

    /** The other names the person goes by; each unique on the sheet among its names and aliases. Loaded
     * by a sub-select, so it loads eagerly beside the grades. */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "person_alias", joinColumns = @JoinColumn(name = "person_id"))
    private List<PersonAlias> aliases = new ArrayList<>();

    /** The person's own terms in days, in place of their grade's and the sheet's; null when they have none. */
    @Column(name = "terms")
    private Integer terms;

    /** The person's own terms dated from a day, at most one per from-date, in place of {@link #terms}
     * from then. Loaded by a sub-select, so it loads eagerly beside the grades. */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SUBSELECT)
    @CollectionTable(name = "person_terms", joinColumns = @JoinColumn(name = "person_id"))
    private List<PersonTerms> datedTerms = new ArrayList<>();

    /** The person's contact, without surrounding spaces and in lower case, unique on the sheet; null
     * when they have none. */
    @Column(name = "contact")
    private String contact;

    /** The person's tags: trimmed, lower-cased, without repeats, sorted and comma separated; null when
     * they have none (see {@link net.officefloor.hq.app.claim.Claim#tagsOf}). */
    @Column(name = "tags")
    private String tags;

    /** The sheet the person was transferred from; null when they were not transferred. */
    @Column(name = "origin_sheet_id")
    private String originSheetId;

    /** The person's place in the sheet's list: people are listed by it, lowest first. */
    @Column(name = "list_position", nullable = false)
    private Long position;

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

    /** A copy of the person on the sheet {@code sheetId}: the same name, every grade they were given
     * (with its from-date), their terms, contact and aliases, noting this sheet as the one they came
     * from; nothing else of theirs comes with it. */
    public Person transferredTo(String sheetId) {
        Person copy = new Person();
        copy.sheetId = sheetId;
        copy.originSheetId = this.sheetId;
        copy.setName(name);
        grades.forEach(g -> copy.grades.add(new PersonGrade(g.getGrade(), g.getFrom())));
        copy.terms = terms;
        datedTerms.forEach(t -> copy.datedTerms.add(new PersonTerms(t.getTerms(), t.getFrom())));
        copy.contact = contact;
        copy.tags = tags;
        aliases.forEach(a -> copy.aliases.add(new PersonAlias(a.getAlias())));
        return copy;
    }

    /** A copy of the person on the sheet {@code sheetId}, a clone of theirs: the same name, place in the
     * list, every grade (with its from-date), terms, contact, aliases, holds, retirement and past
     * retirements. */
    public Person clonedTo(String sheetId) {
        Person copy = new Person();
        copy.sheetId = sheetId;
        copy.setName(name);
        grades.forEach(g -> copy.grades.add(new PersonGrade(g.getGrade(), g.getFrom())));
        copy.retiredFrom = retiredFrom;
        retirements.forEach(r -> copy.retirements.add(new PersonRetirement(r.getFrom(), r.getTo())));
        holds.forEach(h -> copy.holds.add(new PersonHold(h.getFrom(), h.getTo())));
        aliases.forEach(a -> copy.aliases.add(new PersonAlias(a.getAlias())));
        copy.terms = terms;
        datedTerms.forEach(t -> copy.datedTerms.add(new PersonTerms(t.getTerms(), t.getFrom())));
        copy.contact = contact;
        copy.tags = tags;
        copy.position = position;
        return copy;
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

    /** Gives the person {@code terms} (none when null) from the beginning of time, keeping their dated terms. */
    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    /** The person's dated terms, by from-date. */
    public List<PersonTerms> getDatedTerms() {
        return datedTerms.stream().sorted(Comparator.comparing(PersonTerms::getFrom)).toList();
    }

    /** Gives the person {@code terms} (none when null) from {@code from}, in place of those given from the same date. */
    public void setTerms(Integer terms, LocalDate from) {
        datedTerms.removeIf(t -> from.equals(t.getFrom()));
        datedTerms.add(new PersonTerms(terms, from));
    }

    /** The person's own terms in force on {@code date}: those with the latest from-date on or before it,
     * else their undated terms; null when they have none then. */
    public Integer termsOn(LocalDate date) {
        Integer in = terms;
        for (PersonTerms t : getDatedTerms()) {
            if (date != null && t.getFrom().isAfter(date)) break;
            in = t.getTerms();
        }
        return in;
    }

    /** The form a contact is kept in: without surrounding spaces, in lower case; null for a blank one. */
    public static String contactOf(String contact) {
        if (contact == null || contact.isBlank()) return null;
        return contact.trim().toLowerCase(Locale.ROOT);
    }

    public String getContact() {
        return contact;
    }

    /** Gives the person {@code contact} (none when blank), kept in its {@link #contactOf stored form}. */
    public void setContact(String contact) {
        this.contact = contactOf(contact);
    }

    /** The person's tags, sorted; empty when they have none. */
    public List<String> getTags() {
        return tags == null ? List.of() : List.of(tags.split(","));
    }

    /** Gives the person the tags, as {@link net.officefloor.hq.app.claim.Claim#tagsOf} makes them (none
     * when null). */
    public void setTags(String tags) {
        this.tags = tags;
    }

    /** The person's aliases, sorted as names are compared. */
    public List<String> getAliases() {
        return aliases.stream()
                .sorted(Comparator.comparing(PersonAlias::getAliasKey).thenComparing(PersonAlias::getAlias))
                .map(PersonAlias::getAlias)
                .toList();
    }

    /** Gives the person the alias {@code alias}, kept without the spaces around it. */
    public void addAlias(String alias) {
        aliases.add(new PersonAlias(alias));
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

    /** The person's past retirements, by from-date. */
    public List<PersonRetirement> getRetirements() {
        return retirements.stream()
                .sorted(Comparator.comparing(PersonRetirement::getFrom).thenComparing(PersonRetirement::getTo))
                .toList();
    }

    /** Ends the person's retirement on {@code on}, keeping it as a past retirement; they are no longer retired. */
    public void unretire(LocalDate on) {
        retirements.add(new PersonRetirement(retiredFrom, on));
        retiredFrom = null;
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

    /** Refuses a claim dated {@code date} when it falls after the person's retirement date, or after the
     * from-date and before the to-date of a past retirement. */
    public void requireClaimable(LocalDate date) {
        if ((retiredFrom != null && date.isAfter(retiredFrom)) || retirements.stream().anyMatch(r -> r.excludes(date))) {
            throw new PersonRetiredException();
        }
    }

    /** The sheet the person was transferred from; null when they were not transferred. */
    public String getOriginSheetId() {
        return originSheetId;
    }

    public Long getPosition() {
        return position;
    }

    public void setPosition(Long position) {
        this.position = position;
    }

    public long getVersion() {
        return version;
    }
}
