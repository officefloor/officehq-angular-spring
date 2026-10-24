package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A sheet: the unit everything else in the app is kept under. Created the first time it is loaded. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's own today: every date-dependent decision on the sheet uses it, never the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The most a clerk may settle a claim for; null when there is no limit. */
    @Column(name = "clerk_limit", precision = 12, scale = 2)
    private BigDecimal clerkLimit;

    /** The least a claim's live due can be. */
    @Column(name = "due_floor", nullable = false, precision = 12, scale = 2)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The most a person's due-total can be; null when there is no cap. */
    @Column(name = "due_cap", precision = 12, scale = 2)
    private BigDecimal cap;

    /** How many days after its date a claim is due by. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    /** The percent of its figure an overdue claim's due gains for every full 30 days it is overdue. */
    @Column(name = "late_pct", nullable = false, precision = 12, scale = 2)
    private BigDecimal latePct = new BigDecimal("0.00");

    /** The date that decides how a claim's live figures round: half up when the claim is dated before
     * it, half to even on or after it; null when it is the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** The date from which a claim opened is numbered within its period; null when that is off. */
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

    public BigDecimal getCap() {
        return cap;
    }

    public void setCap(BigDecimal cap) {
        this.cap = cap;
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

    /** The policy date in force: the one set, or the sheet's today when none is. */
    public LocalDate policyDateInForce() {
        return policyDate == null ? today : policyDate;
    }

    public LocalDate getPeriodNumberingFrom() {
        return periodNumberingFrom;
    }

    public void setPeriodNumberingFrom(LocalDate periodNumberingFrom) {
        this.periodNumberingFrom = periodNumberingFrom;
    }

    /** Whether a claim opened now is numbered within its period: the setting is a date and the sheet's
     * today is on or after it. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }
}
