package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A sheet: the unit everything else in the app is kept under. Created on its first load. */
@Entity
@Table(name = "sheet")
public class Sheet {

    /** The floor a sheet starts with. */
    public static final BigDecimal DEFAULT_FLOOR = new BigDecimal("1.00");

    /** The terms a sheet starts with, in days. */
    public static final int DEFAULT_TERMS = 14;

    /** The late percent a sheet starts with. */
    public static final BigDecimal DEFAULT_LATE_PCT = new BigDecimal("0.00");

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's today: every date-dependent decision and default date uses it, never the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last claim number given on the sheet; the next claim opened takes the one after. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The most a clerk may settle a claim's due at; null for no limit. */
    @Column(name = "clerk_limit", precision = 14, scale = 2)
    private BigDecimal clerkLimit;

    /** The least a claim's live due can be; 1.00 on a new sheet. */
    @Column(name = "due_floor", nullable = false, precision = 14, scale = 2)
    private BigDecimal floor = DEFAULT_FLOOR;

    /** The days after its date a claim is due by; 14 on a new sheet. */
    @Column(name = "terms", nullable = false)
    private int terms = DEFAULT_TERMS;

    /** The percent of its figure an overdue claim gains for every full 30 days overdue; 0.00 on a new sheet. */
    @Column(name = "late_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal latePct = DEFAULT_LATE_PCT;

    /** The date a claim's rounding turns on: half up before it, half to even on or after; null for the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** The date numbering per period starts: on or after it a claim opened is numbered within its period; null for off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** The last number given in the sheet's X sequence, to claims opened under numbering per period in no period. */
    @Column(name = "last_unperiod_claim_number", nullable = false)
    private int lastUnperiodClaimNumber;

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

    /** Whether a claim opened on the sheet's today is numbered within its period. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** Give out the next number in the sheet's X sequence, for a claim in no period. */
    public int nextUnperiodClaimNumber() {
        return ++lastUnperiodClaimNumber;
    }

    /** Give out the next claim number on the sheet. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
