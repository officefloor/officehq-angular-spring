package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/** A sheet: the unit everything else in the app is kept under, chosen by its id. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's today: used for every date-dependent decision and default instead of the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last number given to a claim opened on this sheet; 0 before the first. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** A clerk can settle a claim only when its due is at or below this; null means no limit. */
    @Column(name = "clerk_limit")
    private BigDecimal clerkLimit;

    /** A claim's live due is at least this (two decimals); fixed figures are left as they are. */
    @Column(name = "floor", nullable = false, precision = 14, scale = 2)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The sheet's terms in days: a claim is due by its date plus these. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    /** The sheet's late percent (two decimals): an overdue claim's due gains this percent of its figure for every
     *  full 30 days it is overdue. */
    @Column(name = "late_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal latePct = new BigDecimal("0.00");

    /** The sheet's policy date: a claim dated before it rounds half up, one on or after it half to even; null means
     *  the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** The sheet's period numbering from: while its today is on or after this, a claim opened is numbered within
     *  its period; null means off. */
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

    /** Whether a claim opened now is numbered within its period: the period numbering from is set and the
     *  sheet's today is on or after it. */
    public boolean numbersPerPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** How a claim's figures round when the claim (or, for a correction, the claim it corrects) is dated on this
     *  day: half up before the policy date (the sheet's today when it has none), half to even on or after it. */
    public RoundingMode roundingFor(LocalDate date) {
        LocalDate policy = policyDate != null ? policyDate : today;
        return date.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /** Take the next claim number on this sheet; it is never given out again. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
