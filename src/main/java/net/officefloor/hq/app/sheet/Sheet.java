package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/** A sheet: everything else in the app is kept per sheet. It starts empty when first loaded. */
@Entity
@Table(name = "sheet")
public class Sheet {

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

    /** The clerk limit: a claim a clerk settles whose due is above it goes PENDING for a steward; null for no limit. */
    @Column(name = "clerk_limit")
    private BigDecimal clerkLimit;

    /** The cap: a person's due-total is at most it; null for no cap. */
    @Column(name = "cap")
    private BigDecimal cap;

    /** The floor: a claim's live due is at least it. */
    @Column(name = "floor", nullable = false)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The terms: a claim is due by its date plus this many days. */
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

    /** The late percent: an overdue claim's due gains it of its figure for every full 30 days overdue. */
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

    /** The clerk limit, with two decimals; null when there is none. */
    public BigDecimal getClerkLimit() {
        return clerkLimit;
    }

    public void setClerkLimit(BigDecimal clerkLimit) {
        this.clerkLimit = clerkLimit;
    }

    /** The cap, with two decimals; null when there is none. */
    public BigDecimal getCap() {
        return cap;
    }

    public void setCap(BigDecimal cap) {
        this.cap = cap;
    }

    /** A sum of figures held to the cap: the cap when the sum is above it; null (n/a) stays null. */
    public BigDecimal capped(BigDecimal sum) {
        return sum != null && cap != null && sum.compareTo(cap) > 0 ? cap : sum;
    }

    /** The floor, with two decimals. */
    public BigDecimal getFloor() {
        return floor;
    }

    public void setFloor(BigDecimal floor) {
        this.floor = floor;
    }

    /** The terms, in days. */
    public int getTerms() {
        return terms;
    }

    public void setTerms(int terms) {
        this.terms = terms;
    }

    /** A grade's own terms in days; null when it has none and uses the sheet's terms. */
    public Integer getGradeTerms(String grade) {
        return switch (grade) {
            case "A" -> termsA;
            case "B" -> termsB;
            case "C" -> termsC;
            default -> throw new IllegalArgumentException("No such grade: " + grade);
        };
    }

    public void setGradeTerms(String grade, Integer terms) {
        switch (grade) {
            case "A" -> termsA = terms;
            case "B" -> termsB = terms;
            case "C" -> termsC = terms;
            default -> throw new IllegalArgumentException("No such grade: " + grade);
        }
    }

    /** The terms a grade's claims are due by: its own, else the sheet's. */
    public int termsFor(String grade) {
        Integer own = grade == null ? null : getGradeTerms(grade);
        return own != null ? own : terms;
    }

    /** The late percent, with two decimals. */
    public BigDecimal getLatePct() {
        return latePct;
    }

    public void setLatePct(BigDecimal latePct) {
        this.latePct = latePct;
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
