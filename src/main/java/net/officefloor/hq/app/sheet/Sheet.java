package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A sheet: everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    /** The floor a sheet starts with. */
    public static final BigDecimal DEFAULT_FLOOR = new BigDecimal("1.00");

    /** The terms (in days) a sheet starts with. */
    public static final int DEFAULT_TERMS = 14;

    /** The late percent a sheet starts with. */
    public static final BigDecimal DEFAULT_LATE_PCT = new BigDecimal("0.00");

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /** The sheet's "today": every date decision and default date on the sheet uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last number given to a claim opened on the sheet; 0 before the first. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** A clerk settles a claim only when its due is at or below this; null when there is no limit. */
    @Column(name = "clerk_limit", precision = 15, scale = 2)
    private BigDecimal clerkLimit;

    /** A claim's live due is at least this. */
    @Column(name = "due_floor", nullable = false, precision = 15, scale = 2)
    private BigDecimal floor = DEFAULT_FLOOR;

    /** A claim is due by its date plus this many days. */
    @Column(name = "terms", nullable = false)
    private int terms = DEFAULT_TERMS;

    /** An overdue claim's due gains this percent of its figure for every full 30 days it is overdue. */
    @Column(name = "late_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal latePct = DEFAULT_LATE_PCT;

    /** A claim dated before this rounds half up, one on or after it half to even; null when blank,
     *  when the sheet's today is the policy date. */
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

    /** The policy date in force: the one set, else the sheet's today. */
    public LocalDate getPolicyDateInForce() {
        return policyDate != null ? policyDate : today;
    }

    /** Take the sheet's next claim number. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
