package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/** A sheet, chosen by its id. Everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's own today: date decisions and default dates use it, never the real clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The most a clerk may settle a claim for; null means no limit. */
    @Column(name = "clerk_limit")
    private BigDecimal clerkLimit;

    /** The least a claim's live due can be. */
    @Column(name = "due_floor", nullable = false, precision = 14, scale = 2)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The days a claim has to be settled in: its due-by is its date plus these. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    /** The percent of an overdue claim's figure added for every full 30 days it is overdue. */
    @Column(name = "late_pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal latePct = new BigDecimal("0.00");

    /** The date claims round by: one before it rounds half up, one on or after it half to even; null for today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    protected Sheet() {
    }

    public Sheet(String id) {
        this.id = id;
        this.createdAt = Instant.now();
        this.today = LocalDate.now();
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

    /**
     * How a claim's live figures round, by the date its rounding follows: half up before the policy date (the
     * sheet's today when it is blank), half to even on or after it.
     */
    public RoundingMode roundingOn(LocalDate roundsOn) {
        LocalDate policy = policyDate != null ? policyDate : today;
        return policy != null && roundsOn.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }
}
