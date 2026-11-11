package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** A sheet: everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /** The sheet's own today: every date-dependent decision and default date uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The highest due a clerk may settle; null when there is no limit. */
    @Column(name = "clerk_limit", precision = 12, scale = 2)
    private BigDecimal clerkLimit;

    /** The least a claim's live due can be. */
    @Column(name = "due_floor", nullable = false, precision = 12, scale = 2)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The number of days after its date that a claim is due by. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    /** The percent of its figure an overdue claim's due gains for every full 30 days it is overdue. */
    @Column(name = "late_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal latePct = new BigDecimal("0.00");

    /** The policy date: a claim dated before it rounds half up, one on or after it half to even; null: the today. */
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

    /**
     * How a claim rounding by {@code date} rounds its live figures: half up when dated before the policy date (the
     * sheet's today when it has none), half to even on or after it.
     */
    public RoundingMode rounding(LocalDate date) {
        return date.isBefore(policyDate != null ? policyDate : today) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /** The rate step: the amount at the percent, to two decimals rounded as a claim rounding by the date does. */
    public BigDecimal rateStep(BigDecimal amount, BigDecimal pct, LocalDate roundingDate) {
        return amount.multiply(pct).movePointLeft(2).setScale(2, rounding(roundingDate));
    }

    /**
     * The late charge on a figure of an OPEN claim dated {@code date}: the late percent of the figure for every full
     * 30 days the sheet's today is past the claim's due-by (its date plus the terms), rounded as the claim's date has it;
     * zero when not overdue, null (n/a) when the figure is.
     */
    public BigDecimal lateCharge(BigDecimal figure, LocalDate date) {
        if (figure == null) {
            return null;
        }
        long overdueDays = ChronoUnit.DAYS.between(date.plusDays(terms), today);
        long periods = overdueDays <= 0 ? 0 : overdueDays / 30;
        return figure.multiply(latePct).multiply(BigDecimal.valueOf(periods)).movePointLeft(2)
                .setScale(2, rounding(date));
    }

    /** The figure with its late charge added on top when the claim is OPEN; null (n/a) stays null. */
    public BigDecimal withLateCharge(BigDecimal figure, boolean open, LocalDate date) {
        return figure == null || !open ? figure : figure.add(lateCharge(figure, date));
    }

    /** A live due raised to the floor; null (n/a) stays null. */
    public BigDecimal floored(BigDecimal due) {
        return due == null ? null : due.max(floor);
    }
}
