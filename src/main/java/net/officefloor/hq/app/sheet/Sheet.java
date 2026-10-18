package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A sheet: the unit everything else in the app is kept under. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's "today": every date-dependent decision in the sheet uses it, never the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last claim number the sheet gave out; the next opened claim gets one more. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** A clerk may settle a claim only when its due is at or below this; none: no limit. */
    @Column(name = "clerk_limit", precision = 15, scale = 2)
    private BigDecimal clerkLimit;

    /** A claim's live due is at least this; a fixed, bound or closed-period figure is not changed by it. */
    @Column(name = "floor", nullable = false, precision = 15, scale = 2)
    private BigDecimal floor = new BigDecimal("1.00");

    /** A claim is due by its date plus this many days. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    /** An overdue claim gains this percent of its figure for every full 30 days it is overdue. */
    @Column(name = "late_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal latePct = new BigDecimal("0.00");

    /** A claim dated before this rounds half up, one on or after it half to even; none: the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    protected Sheet() {}

    public Sheet(String id, String createdBy, Instant createdAt, LocalDate today) {
        this.id = id;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.today = today;
    }

    public String getId() { return id; }

    public String getCreatedBy() { return createdBy; }

    public Instant getCreatedAt() { return createdAt; }

    public LocalDate getToday() { return today; }

    public void setToday(LocalDate today) { this.today = today; }

    public BigDecimal getClerkLimit() { return clerkLimit; }

    public void setClerkLimit(BigDecimal clerkLimit) { this.clerkLimit = clerkLimit; }

    public BigDecimal getFloor() { return floor; }

    public void setFloor(BigDecimal floor) { this.floor = floor; }

    public int getTerms() { return terms; }

    public void setTerms(int terms) { this.terms = terms; }

    public BigDecimal getLatePct() { return latePct; }

    public void setLatePct(BigDecimal latePct) { this.latePct = latePct; }

    public LocalDate getPolicyDate() { return policyDate; }

    public void setPolicyDate(LocalDate policyDate) { this.policyDate = policyDate; }

    /** The policy date in force: the one set, else the sheet's today. */
    public LocalDate policyDateInForce() { return policyDate != null ? policyDate : today; }

    /** Give out the sheet's next claim number. */
    public int takeClaimNumber() { return ++lastClaimNumber; }
}
