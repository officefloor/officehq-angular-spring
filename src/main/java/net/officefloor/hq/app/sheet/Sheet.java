package net.officefloor.hq.app.sheet;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** A sheet: everything else in the app is kept per sheet. It starts empty when first loaded. */
@Entity
@Table(name = "sheet")
public class Sheet {

    /** The keys of the settings that carry a figure, each dated. */
    public static final String CLERK_LIMIT = "clerk-limit";
    public static final String CAP = "cap";
    public static final String FLOOR = "floor";
    public static final String TERMS = "terms";
    public static final String LATE_PCT = "late-pct";

    @Id
    private String id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's today: every date-dependent decision on the sheet uses it, never the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last claim number the sheet gave out; 0 before any claim is opened. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The starting clerk limit (before every from-date): a claim a clerk settles whose due is above it goes PENDING for a steward; null for no limit. */
    @Column(name = "clerk_limit")
    private BigDecimal clerkLimit;

    /** The starting cap (before every from-date): a person's due-total is at most it; null for no cap. */
    @Column(name = "cap")
    private BigDecimal cap;

    /** The starting floor (before every from-date): a claim's live due is at least it. */
    @Column(name = "floor", nullable = false)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The starting terms (before every from-date): a claim is due by its date plus this many days. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    /** Grade A's own terms in days; null when it uses the sheet's terms. */
    @Column(name = "terms_a")
    private Integer termsA;

    /** Grade B's own terms in days; null when it uses the sheet's terms. */
    @Column(name = "terms_b")
    private Integer termsB;

    /** Grade C's own terms in days; null when it uses the sheet's terms. */
    @Column(name = "terms_c")
    private Integer termsC;

    /** The starting late percent (before every from-date): an overdue claim's due gains it of its figure for every full 30 days overdue. */
    @Column(name = "late_pct", nullable = false)
    private BigDecimal latePct = new BigDecimal("0.00");

    /** The policy date: a claim dated before it rounds half up, on or after it half to even; null for the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** Period numbering from: a claim opened on or after it is numbered within its period; null for off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** The last X-number given to a claim in no period while period numbering is on; 0 before any. */
    @Column(name = "last_unperiod_claim_number", nullable = false)
    private int lastUnperiodClaimNumber;

    /** The values settings carry from a date; before every from-date a setting has its column's value. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_setting", joinColumns = @JoinColumn(name = "sheet_id"))
    private List<DatedSetting> dated = new ArrayList<>();

    protected Sheet() {
    }

    public Sheet(String id, Instant createdAt, LocalDate today) {
        this.id = id;
        this.createdAt = createdAt;
        this.today = today;
    }

    public String getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public LocalDate getToday() {
        return today;
    }

    public void setToday(LocalDate today) {
        this.today = today;
    }

    /** The clerk limit in force on the sheet's today, with two decimals; null when there is none. */
    public BigDecimal getClerkLimit() {
        return decimal(valueOn(CLERK_LIMIT, today, plain(clerkLimit)));
    }

    /** The cap in force on the sheet's today, with two decimals; null when there is none. */
    public BigDecimal getCap() {
        return decimal(valueOn(CAP, today, plain(cap)));
    }

    /** A sum of figures held to the cap in force today: the cap when the sum is above it; null (n/a) stays null. */
    public BigDecimal capped(BigDecimal sum) {
        BigDecimal cap = getCap();
        return sum != null && cap != null && sum.compareTo(cap) > 0 ? cap : sum;
    }

    /** The floor in force on the sheet's today, with two decimals. */
    public BigDecimal getFloor() {
        return floorOn(today);
    }

    /** The floor in force on a date, with two decimals. */
    public BigDecimal floorOn(LocalDate date) {
        return decimal(valueOn(FLOOR, date, plain(floor)));
    }

    /** The terms in force on the sheet's today, in days. */
    public int getTerms() {
        return termsOn(today);
    }

    /** The sheet's terms in force on a date, in days. */
    public int termsOn(LocalDate date) {
        return Integer.parseInt(valueOn(TERMS, date, Integer.toString(terms)));
    }

    /** A grade's own terms in force on the sheet's today in days; null when it uses the sheet's terms. */
    public Integer getGradeTerms(String grade) {
        return gradeTermsOn(grade, today);
    }

    /** A grade's own terms in force on a date in days; null when it uses the sheet's terms. */
    public Integer gradeTermsOn(String grade, LocalDate date) {
        Integer start = switch (grade) {
            case "A" -> termsA;
            case "B" -> termsB;
            case "C" -> termsC;
            default -> throw new IllegalArgumentException("No such grade: " + grade);
        };
        String value = valueOn(gradeTermsKey(grade), date, start == null ? null : start.toString());
        return value == null ? null : Integer.valueOf(value);
    }

    /** The terms a grade's claims dated on a date are due by: its own in force then, else the sheet's. */
    public int termsFor(String grade, LocalDate date) {
        Integer own = grade == null ? null : gradeTermsOn(grade, date);
        return own != null ? own : termsOn(date);
    }

    /** The late percent in force on the sheet's today, with two decimals. */
    public BigDecimal getLatePct() {
        return latePctOn(today);
    }

    /** The late percent in force on a date, with two decimals. */
    public BigDecimal latePctOn(LocalDate date) {
        return decimal(valueOn(LATE_PCT, date, plain(latePct)));
    }

    /** The key a grade's own terms are recorded under. */
    public static String gradeTermsKey(String grade) {
        return TERMS + "-" + grade;
    }

    /**
     * Record a setting's value (null: none) from a date, keeping the values already recorded; a value
     * already recorded from that date is replaced.
     */
    public void record(String key, LocalDate from, String value) {
        dated.removeIf(d -> d.getKey().equals(key) && d.getFromDate().equals(from));
        dated.add(new DatedSetting(key, from, value));
    }

    /**
     * A setting's values: its starting value (with no from-date) and then each recorded from a date,
     * by from-date.
     */
    public List<DatedSetting> history(String key, String start) {
        List<DatedSetting> all = new ArrayList<>();
        all.add(new DatedSetting(key, null, start));
        dated.stream().filter(d -> d.getKey().equals(key))
                .sorted(Comparator.comparing(DatedSetting::getFromDate)).forEach(all::add);
        return all;
    }

    /** The floor's values (see {@link #history}). */
    public List<DatedSetting> floorHistory() {
        return history(FLOOR, plain(floor));
    }

    /** The late percent's values (see {@link #history}). */
    public List<DatedSetting> latePctHistory() {
        return history(LATE_PCT, plain(latePct));
    }

    /**
     * A setting's value in force on a date: the one recorded with the latest from-date on or before
     * it (which may be null, for none), else the starting value.
     */
    private String valueOn(String key, LocalDate date, String start) {
        Optional<DatedSetting> recorded = dated.stream()
                .filter(d -> d.getKey().equals(key) && !d.getFromDate().isAfter(date))
                .max(Comparator.comparing(DatedSetting::getFromDate));
        return recorded.isPresent() ? recorded.get().getValue() : start;
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    /** The policy date as set; null when blank (the sheet's today is then the policy date). */
    public LocalDate getPolicyDate() {
        return policyDate;
    }

    public void setPolicyDate(LocalDate policyDate) {
        this.policyDate = policyDate;
    }

    /**
     * How the live figures of a claim dated on a date round: half up when the date is before the
     * policy date (the sheet's today when none is set), else half to even.
     */
    public RoundingMode roundingOn(LocalDate date) {
        LocalDate policy = policyDate == null ? today : policyDate;
        return date.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /** The period-numbering-from date as set; null when blank (off). */
    public LocalDate getPeriodNumberingFrom() {
        return periodNumberingFrom;
    }

    public void setPeriodNumberingFrom(LocalDate periodNumberingFrom) {
        this.periodNumberingFrom = periodNumberingFrom;
    }

    /** Whether a claim opened now is numbered within its period: the setting is a date on or before today. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** Give out the sheet's next number for a claim in no period: 1 for the first, then 2, and so on. */
    public int nextUnperiodClaimNumber() {
        return ++lastUnperiodClaimNumber;
    }

    /** Give out the sheet's next claim number: 1 for the first, then 2, and so on. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
