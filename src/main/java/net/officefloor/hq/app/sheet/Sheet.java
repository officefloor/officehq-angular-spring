package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A sheet, identified by the id a user types; everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /** The sheet's today: every date-dependent decision and default date uses it, never the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last claim number given on the sheet; only ever moved on by {@link SheetRepository#nextClaimNumber}. */
    @Column(name = "last_claim_number", insertable = false, updatable = false)
    private int lastClaimNumber;

    /** The most due a clerk may settle on a claim; null when there is no limit. */
    @Column(name = "clerk_limit", precision = 14, scale = 2)
    private BigDecimal clerkLimit;

    /** The least live due a claim on the sheet has. */
    @Column(name = "due_floor", nullable = false, precision = 14, scale = 2)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The days after its date a claim is due by. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    /** The percent of its figure an overdue claim gains for every full 30 days it is overdue. */
    @Column(name = "late_pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal latePct = new BigDecimal("0.00");

    /** The policy date: a claim dated before it rounds half up, one on or after it half to even; null
     *  (blank) takes the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    protected Sheet() {
    }

    public Sheet(String id, String createdBy, LocalDate today) {
        this.id = id;
        this.createdBy = createdBy;
        this.today = today;
    }

    public String getId() {
        return id;
    }

    public String getCreatedBy() {
        return createdBy;
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
}
