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
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
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

    /** Whether the person is on hold: their OPEN claims count in no live total. */
    @Column(name = "held", nullable = false)
    private boolean held;

    /** The ids of the other people on the sheet the person has named as their patrons. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "person_patron", joinColumns = @JoinColumn(name = "person_id"))
    @Column(name = "patron_id", nullable = false)
    private Set<Long> patronIds = new HashSet<>();

    protected Person() {
    }

    public Person(String sheetId, String name, String grade) {
        this.sheetId = sheetId;
        setName(name);
        this.grade = grade;
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

    public boolean isHeld() {
        return held;
    }

    /** Put the person on hold, or take them off it. */
    public void setHeld(boolean held) {
        this.held = held;
    }

    /**
     * Whether a claim of the person in this status counts in a live total: OPEN and SETTLED claims do,
     * but not an OPEN one while the person is on hold.
     */
    public boolean countsLive(ClaimStatus status) {
        return status.counts() && !(held && status == ClaimStatus.OPEN);
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

    public Set<Long> getPatronIds() {
        return Collections.unmodifiableSet(patronIds);
    }

    /** Name another person as a patron; false when they already are one. */
    public boolean addPatron(Long patronId) {
        return patronIds.add(patronId);
    }
}
