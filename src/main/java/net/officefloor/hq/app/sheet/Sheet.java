package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** A sheet, chosen by its id. Everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's today: every date decision and default date in the sheet uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last number given to a claim opened on the sheet; 0 before the first. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The largest due a clerk may settle; none when there is no limit. */
    @Column(name = "clerk_limit")
    private BigDecimal clerkLimit;

    /** The least a claim's live due can be; a sheet starts at 1.00. */
    @Column(name = "due_floor", nullable = false)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The number of days after a claim's date it is due by; a sheet starts at 14. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    /** The percent of its figure an overdue claim's due gains for every full 30 days it is overdue; a sheet
     *  starts at 0.00. */
    @Column(name = "late_pct", nullable = false)
    private BigDecimal latePct = new BigDecimal("0.00");

    /** The policy date: a claim dated before it rounds half up, on or after it half to even; none means
     *  the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** Period numbering from this date: while the sheet's today is on or after it, a claim opened is numbered
     *  within its period; none means off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    protected Sheet() {
    }

    public Sheet(String id, String createdBy, Instant createdAt, LocalDate today) {
        this.id = id;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.today = today;
    }

    public String getId() {
        return id;
    }

    public String getCreatedBy() {
        return createdBy;
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

    public BigDecimal getClerkLimit() {
        return clerkLimit;
    }

    public void setClerkLimit(BigDecimal clerkLimit) {
        this.clerkLimit = clerkLimit;
    }

    public BigDecimal getFloor() {
        return floor;
    }

    public void setFloor(BigDecimal floor) {
        this.floor = floor;
    }

    public int getTerms() {
        return terms;
    }

    public void setTerms(int terms) {
        this.terms = terms;
    }

    public BigDecimal getLatePct() {
        return latePct;
    }

    public void setLatePct(BigDecimal latePct) {
        this.latePct = latePct;
    }

    public LocalDate getPolicyDate() {
        return policyDate;
    }

    public void setPolicyDate(LocalDate policyDate) {
        this.policyDate = policyDate;
    }

    public LocalDate getPeriodNumberingFrom() {
        return periodNumberingFrom;
    }

    public void setPeriodNumberingFrom(LocalDate periodNumberingFrom) {
        this.periodNumberingFrom = periodNumberingFrom;
    }

    /** Whether a claim opened now is numbered within its period: the setting is a date and today is on or
     *  after it. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** How a claim dated on a date rounds its figures: half up before the policy date (the sheet's today
     *  when it has none), half to even on or after it. */
    public RoundingMode rounding(LocalDate date) {
        LocalDate policy = policyDate == null ? today : policyDate;
        return date.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /** The late charge on an overdue claim's figure: the late percent of it for every full 30 days past its
     *  due-by (its date plus the terms) on the sheet's today, rounded to cents as its date rounds; 0.00 for no
     *  figure (n/a). Only an OPEN or PENDING claim is overdue, so the caller asks only for those. */
    public BigDecimal lateCharge(BigDecimal figure, LocalDate date) {
        long late = ChronoUnit.DAYS.between(date.plusDays(terms), today);
        if (figure == null || late < 30) {
            return BigDecimal.ZERO.setScale(2);
        }
        return figure.multiply(latePct).multiply(BigDecimal.valueOf(late / 30)).movePointLeft(2)
                .setScale(2, rounding(date));
    }

    /** A live due as it counts: at least the floor; none (n/a) stays none. */
    public BigDecimal floored(BigDecimal due) {
        return due == null ? null : due.max(floor);
    }

    /** Give out the sheet's next claim number. */
    public String nextClaimNumber() {
        return String.valueOf(++lastClaimNumber);
    }
}
