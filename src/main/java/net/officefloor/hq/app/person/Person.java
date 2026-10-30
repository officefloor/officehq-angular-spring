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
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.officefloor.hq.app.claim.Claim;
import net.officefloor.hq.app.claim.Dues;
import net.officefloor.hq.app.rate.Grades;
import net.officefloor.hq.app.rate.RateTable;
import java.util.Objects;
import java.util.Optional;
import net.officefloor.hq.app.claim.Holds;

/**
 * A person on a sheet, with a name, their own terms (null: none), each from a date (see {@link #termsOn}), and their grades (A, B or C), each from a date (see {@link #gradeOn}), the date they are retired from (null while
 * not retired), their past retirements (see {@link #getRetirements}), their holds (see {@link #heldOn}) and the version it was last saved at.
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
    private List<GradeFrom> grades = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_alias", joinColumns = @JoinColumn(name = "person_id"))
    @Column(name = "alias")
    private List<String> aliases = new ArrayList<>();

    @Column(name = "terms")
    private Integer terms;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_terms", joinColumns = @JoinColumn(name = "person_id"))
    private List<TermsFrom> datedTerms = new ArrayList<>();

    @Column(name = "contact")
    private String contact;

    @Column(name = "tags", nullable = false)
    private String tags = "";

    @Column(name = "origin")
    private String origin;

    @Column(name = "arrived_on")
    private LocalDate arrivedOn;

    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    @Column(name = "flag_reason")
    private String flagReason;

    @Column(name = "flag_until")
    private LocalDate flagUntil;

    @Column(name = "approver")
    private String approver;

    @Column(name = "list_position", nullable = false)
    private int position;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    @OrderBy("fromDate ASC")
    private List<HoldSpan> holds = new ArrayList<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_retirement", joinColumns = @JoinColumn(name = "person_id"))
    @OrderBy("fromDate ASC")
    private List<RetirementSpan> retirements = new ArrayList<>();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        this.name = name;
        this.grades.add(new GradeFrom(grade, null));
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    /**
     * The person as they arrive on another sheet: their name, every grade they were given (each from its
     * date), their terms, contact, tags and aliases, and this sheet as the one they came from; not their holds nor
     * retirement.
     */
    public Person arriving(String sheetId) {
        Person arrived = new Person();
        arrived.sheetId = sheetId;
        arrived.name = name;
        grades.forEach(g -> arrived.grades.add(new GradeFrom(g.getGrade(), g.getFromDate())));
        arrived.aliases.addAll(aliases);
        arrived.terms = terms;
        datedTerms.forEach(t -> arrived.datedTerms.add(new TermsFrom(t.getTerms(), t.getFromDate())));
        arrived.contact = contact;
        arrived.tags = tags;
        arrived.origin = this.sheetId;
        return arrived;
    }

    /**
     * The person as copied onto a clone of their sheet: everything they have here (grades, terms, contact,
     * aliases, origin, place in the list, holds, retirement and past retirements).
     */
    public Person cloned(String sheetId) {
        Person copy = arriving(sheetId);
        copy.origin = origin;
        copy.arrivedOn = arrivedOn;
        copy.position = position;
        copy.retiredFrom = retiredFrom;
        copy.flagReason = flagReason;
        copy.flagUntil = flagUntil;
        copy.approver = approver;
        holds.forEach(h -> copy.holds.add(new HoldSpan(h.getFromDate(), h.getToDate())));
        retirements.forEach(r -> copy.retirements.add(new RetirementSpan(r.getFromDate(), r.getToDate())));
        return copy;
    }

    /** The person's place in the sheet's list: the list is by position, lowest first. */
    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getVersion() {
        return version;
    }

    /**
     * The grade in force on the date: the one whose from-date is the latest on or before it, the first
     * grade (no from-date) applying from the beginning of time.
     */
    public String gradeOn(LocalDate date) {
        return grades.stream()
                .filter(g -> g.getFromDate() == null || !g.getFromDate().isAfter(date))
                .max(Comparator.comparing(GradeFrom::getFromDate, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(GradeFrom::getGrade).orElse(null);
    }

    /** The grades the person was given, each from its date. */
    public List<GradeFrom> getGrades() {
        return List.copyOf(grades);
    }

    /** Record the grade from the date, keeping the others; one already recorded from that date is replaced. */
    public void gradeFrom(String grade, LocalDate from) {
        grades.removeIf(g -> g.getFromDate() != null && Objects.equals(g.getFromDate(), from));
        grades.add(new GradeFrom(grade, from));
    }

    /** The person's aliases, sorted as names are compared (without regard to case). */
    public List<String> getAliases() {
        return aliases.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    /** Add an alias, stored trimmed, keeping the others. */
    public void addAlias(String alias) {
        aliases.add(alias);
    }

    /** The person's own terms in days from the beginning of time (before any dated terms); null when they have none. */
    public Integer getTerms() {
        return terms;
    }

    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    /**
     * The person's own terms in force on the date: those whose from-date is the latest on or before it,
     * else those from the beginning of time; null when none are in force.
     */
    public Integer termsOn(LocalDate date) {
        Optional<TermsFrom> dated = datedTerms.stream()
                .filter(t -> !t.getFromDate().isAfter(date))
                .max(Comparator.comparing(TermsFrom::getFromDate));
        return dated.isPresent() ? dated.get().getTerms() : terms;
    }

    /** The person's own terms recorded from a date, each from its date. */
    public List<TermsFrom> getDatedTerms() {
        return List.copyOf(datedTerms);
    }

    /**
     * Record the terms (null: none) from the date, keeping the others; those already recorded from that date
     * are replaced. Whether anything changed.
     */
    public boolean termsFrom(Integer terms, LocalDate from) {
        boolean same = datedTerms.stream()
                .anyMatch(t -> t.getFromDate().equals(from) && Objects.equals(t.getTerms(), terms));
        if (same) {
            return false;
        }
        datedTerms.removeIf(t -> t.getFromDate().equals(from));
        datedTerms.add(new TermsFrom(terms, from));
        return true;
    }

    /** The person's contact, trimmed and lower-cased; null when they have none. */
    public String getContact() {
        return contact;
    }

    /** The person's tags, sorted and comma separated ({@code ""} when none; see {@link net.officefloor.hq.app.claim.Tags}). */
    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    /** The sheet the person was transferred from; null when they were not transferred here. */
    public String getOrigin() {
        return origin;
    }

    /** The sheet's today when the person arrived here by transfer; null when they were not transferred here. */
    public LocalDate getArrivedOn() {
        return arrivedOn;
    }

    public void setArrivedOn(LocalDate arrivedOn) {
        this.arrivedOn = arrivedOn;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    /** Whether the person has any hold, whatever its dates. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    /** The person's holds, earliest from-date first. */
    public List<HoldSpan> getHolds() {
        return holds.stream().sorted(Comparator.comparing(HoldSpan::getFromDate)).toList();
    }

    /** Whether a hold covers the date: held on the sheet's today, the person gets no cut of a pool. */
    public boolean heldOn(LocalDate date) {
        return holds.stream().anyMatch(h -> h.covers(date));
    }

    /** Add a hold from a date to an optional date, keeping the others. */
    public void hold(LocalDate from, LocalDate to) {
        holds.add(new HoldSpan(from, to));
    }

    /** Flag the person with a reason until a date, inclusive, replacing any flag they had. */
    public void flag(String reason, LocalDate until) {
        this.flagReason = reason;
        this.flagUntil = until;
    }

    /** The user who may approve or reject the person's PENDING claims as a steward may; null when none. */
    public String getApprover() {
        return approver;
    }

    public void setApprover(String approver) {
        this.approver = approver;
    }

    /** The reason of the person's flag while it is in force on the sheet's today (on or before its until date); null otherwise. */
    public String flagOn(LocalDate today) {
        return flagReason != null && flagUntil != null && !today.isAfter(flagUntil) ? flagReason : null;
    }

    /** Whether the retirement date is reached on the sheet's today: on or after it. */
    public boolean retiredOn(LocalDate today) {
        return retiredFrom != null && !today.isBefore(retiredFrom);
    }

    /** Each of the people's parts of the shared claims given, by person id (see {@link Dues#parts}). */
    public static Map<Long, Dues.Parts> parts(Collection<Person> people, Collection<Claim> claims, RateTable rates) {
        Map<Long, Person> byId = people.stream().collect(Collectors.toMap(Person::getId, Function.identity()));
        Function<Long, Grades> grades = id -> {
            Person person = byId.get(id);
            return person == null ? null : person::gradeOn;
        };
        return Dues.parts(claims, grades, id -> byId.containsKey(id) ? byId.get(id).leaves() : null, rates);
    }

    /** The person's holds and retirement, as they leave claims out of the live totals. */
    public Holds leaves() {
        return new Holds() {
            @Override
            public boolean on(LocalDate date) {
                return heldOn(date);
            }

            @Override
            public boolean retiredOn(LocalDate today) {
                return Person.this.retiredOn(today);
            }
        };
    }

    /** The person's past retirements, earliest from-date first. */
    public List<RetirementSpan> getRetirements() {
        return retirements.stream().sorted(Comparator.comparing(RetirementSpan::getFromDate)).toList();
    }

    /** Unretire the person on the date: their retirement is kept as a past one, to that date. */
    public void unretire(LocalDate on) {
        retirements.add(new RetirementSpan(retiredFrom, on));
        retiredFrom = null;
    }

    /** Whether a claim dated then is refused: after their retirement date, or within a past retirement. */
    public boolean retiredAt(LocalDate date) {
        return (retiredFrom != null && date.isAfter(retiredFrom)) || retirements.stream().anyMatch(r -> r.bars(date));
    }

    /** Whether a retirement date is set: retired at once, whatever the date. */
    public boolean isRetired() {
        return retiredFrom != null;
    }
}
