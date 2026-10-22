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
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.officefloor.hq.app.claim.ClaimStatus;

/**
 * A person on a sheet, with a name and a dated grade (A, B or C): the first grade they were given
 * applies from the beginning of time, and each later one from its own date. The name is kept with surrounding spaces
 * removed and is unique on the sheet ignoring case, by its {@link #keyOf key}. Its version is raised on
 * every save, so an edit made from an older version is refused.
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

    /** The first grade the person was given: in force from the beginning of time. */
    @Column(name = "grade", nullable = false)
    private String grade;

    /** The grades recorded since the first, by the date each is in force from. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_grade", joinColumns = @JoinColumn(name = "person_id"))
    @MapKeyColumn(name = "from_date")
    @Column(name = "grade", nullable = false)
    private Map<LocalDate, String> gradesFrom = new TreeMap<>();

    /** The person's own terms in days; null when they have none and take their grade's terms. */
    @Column(name = "terms")
    private Integer terms;

    /** The person's contact, kept trimmed and lower-cased; null when they have none. Unique on the sheet. */
    @Column(name = "contact")
    private String contact;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** How many statements have been taken for the person. */
    @Column(name = "statement_count", nullable = false)
    private int statementCount;

    /** The last statement's due-total; null with no statement, or when it was n/a. */
    @Column(name = "statement_total", precision = 14, scale = 2)
    private BigDecimal statementTotal;

    /** The sheet's today when the last statement was taken; null with no statement. */
    @Column(name = "statement_date")
    private LocalDate statementDate;

    /** The date the person is retired from; null while they are not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /**
     * The person's holds, in the order they were added: their OPEN and PENDING claims dated within
     * one, unless bound by a statement, count in no live total.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_hold", joinColumns = @JoinColumn(name = "person_id"))
    private List<Hold> holds = new ArrayList<>();

    /** The ids of the other people on the sheet the person has named as their patrons, in the order named. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_patron", joinColumns = @JoinColumn(name = "person_id"))
    @OrderColumn(name = "patron_order")
    @Column(name = "patron_id", nullable = false)
    private List<Long> patronIds = new ArrayList<>();

    /** The other names the person goes by, each unique on the sheet with the names by its key. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_alias", joinColumns = @JoinColumn(name = "person_id"))
    private Set<Alias> aliases = new HashSet<>();

    protected Person() {
    }

    public Person(String sheetId, String name, String grade, Integer terms, String contact) {
        this.sheetId = sheetId;
        setName(name);
        this.grade = grade;
        this.terms = terms;
        setContact(contact);
    }

    /** A contact as it is kept: surrounding spaces removed and lower-cased; null when blank or none. */
    public static String contactOf(String contact) {
        if (contact == null || contact.isBlank()) {
            return null;
        }
        return contact.trim().toLowerCase(Locale.ROOT);
    }

    /** The name as it is kept: surrounding spaces removed. */
    public static String clean(String name) {
        return name.trim();
    }

    /** The key a name is unique by on its sheet: kept as cleaned, ignoring case. */
    public static String keyOf(String name) {
        return clean(name).toLowerCase(Locale.ROOT);
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
        this.name = clean(name);
        this.nameKey = keyOf(name);
    }

    /** The first grade the person was given: in force from the beginning of time. */
    public String getGrade() {
        return grade;
    }

    /** The grades recorded since the first, by the date each is in force from, earliest first. */
    public Map<LocalDate, String> getGradesFrom() {
        return Collections.unmodifiableMap(new TreeMap<>(gradesFrom));
    }

    /**
     * The grade in force on a date: the one recorded from the latest date on or before it, else the
     * first grade the person was given.
     */
    public String gradeOn(LocalDate date) {
        String on = grade;
        LocalDate latest = null;
        for (Map.Entry<LocalDate, String> entry : gradesFrom.entrySet()) {
            if (!entry.getKey().isAfter(date) && (latest == null || entry.getKey().isAfter(latest))) {
                latest = entry.getKey();
                on = entry.getValue();
            }
        }
        return on;
    }

    /** Record a grade from a date, in place of any recorded from that same date. */
    public void gradeFrom(String grade, LocalDate from) {
        gradesFrom.put(from, grade);
    }

    /** The person's own terms in days; null when they have none. */
    public Integer getTerms() {
        return terms;
    }

    /** Set the person's own terms in days; null for none. */
    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    /** The person's contact; null when they have none. */
    public String getContact() {
        return contact;
    }

    /** Set the person's contact, kept as {@link #contactOf cleaned}; blank or null for none. */
    public void setContact(String contact) {
        this.contact = contactOf(contact);
    }

    public long getVersion() {
        return version;
    }

    public int getStatementCount() {
        return statementCount;
    }

    public BigDecimal getStatementTotal() {
        return statementTotal;
    }

    public LocalDate getStatementDate() {
        return statementDate;
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    /** Retire the person from a date: no claim dated after it can be added for them. */
    public void retire(LocalDate from) {
        this.retiredFrom = from;
    }

    /** Whether the person has any hold, whatever its dates. */
    public boolean isHeld() {
        return !holds.isEmpty();
    }

    public List<Hold> getHolds() {
        return Collections.unmodifiableList(holds);
    }

    /** Add a hold from a date, to a date (inclusive) or with no end. */
    public void hold(LocalDate from, LocalDate to) {
        holds.add(new Hold(from, to));
    }

    /** Whether a date falls within any of the person's holds. */
    public boolean heldOn(LocalDate date) {
        return holds.stream().anyMatch(hold -> hold.covers(date));
    }

    /** Whether the person's retirement date is reached on this date (the sheet's today). */
    public boolean retiredOn(LocalDate today) {
        return retiredFrom != null && today != null && !today.isBefore(retiredFrom);
    }

    /**
     * Whether an OPEN or PENDING claim of the person dated on this date, not bound by a statement, is
     * left out of the live totals on the sheet's today: when dated within a hold, or once the
     * person's retirement date is reached.
     */
    public boolean leavesLive(LocalDate date, LocalDate today) {
        return heldOn(date) || retiredOn(today);
    }

    /**
     * Whether a claim of the person in this status, dated on this date and bound by a statement or
     * not, counts in a live total on the sheet's today: OPEN, PENDING and SETTLED claims do, but not an
     * OPEN or PENDING one dated within a hold or once the person's retirement date is reached, unless a
     * statement has bound it, nor one deferred on the sheet's today.
     */
    public boolean countsLive(ClaimStatus status, LocalDate date, boolean stated, boolean deferred, LocalDate today) {
        return status.counts() && !deferred && !(status.isOpen() && !stated && leavesLive(date, today));
    }

    /** Whether a claim may be dated on this date: not after the person's retirement. */
    public boolean canClaimOn(LocalDate date) {
        return retiredFrom == null || !date.isAfter(retiredFrom);
    }

    /** Record a statement taken on a date with its due-total (null when n/a). */
    public void stated(BigDecimal total, LocalDate date) {
        this.statementCount++;
        this.statementTotal = total;
        this.statementDate = date;
    }

    /**
     * Cancel the last statement: one fewer is counted, and the one before it (its due-total, null
     * when n/a, and date) is shown again; with none before it, no statement is shown.
     */
    public void unstated(BigDecimal previousTotal, LocalDate previousDate) {
        this.statementCount--;
        this.statementTotal = previousTotal;
        this.statementDate = previousDate;
    }

    /** The ids of the person's patrons, in the order they were named. */
    public List<Long> getPatronIds() {
        return Collections.unmodifiableList(patronIds);
    }

    /** The person's aliases, sorted as names are compared. */
    public List<String> getAliases() {
        return aliases.stream().sorted(java.util.Comparator.comparing(Alias::getAliasKey).thenComparing(Alias::getAlias))
                .map(Alias::getAlias).toList();
    }

    /** Whether the person goes by this name or alias, compared as names are. */
    public boolean goesBy(String name) {
        String key = keyOf(name);
        return nameKey.equals(key) || aliases.stream().anyMatch(alias -> alias.getAliasKey().equals(key));
    }

    /** Add an alias, kept as names are kept. */
    public void addAlias(String alias) {
        aliases.add(new Alias(alias));
    }

    /** No longer name this person as a patron; false when they were not one. */
    public boolean removePatron(Long patronId) {
        return patronIds.remove(patronId);
    }

    /** Name another person as a patron; false when they already are one. */
    public boolean addPatron(Long patronId) {
        if (patronIds.contains(patronId)) {
            return false;
        }
        return patronIds.add(patronId);
    }
}
