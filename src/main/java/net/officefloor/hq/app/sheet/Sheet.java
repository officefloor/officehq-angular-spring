package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** A sheet, identified by the id a user types to load it. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /** The sheet's "today": every date decision and default date in the sheet uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last number given to a claim opened on the sheet; 0 before the first. */
    @Column(name = "last_claim_number", nullable = false)
    private long lastClaimNumber;

    /** The most due a clerk can settle a claim for; null for no limit. */
    @Column(name = "clerk_limit", precision = 14, scale = 2)
    private BigDecimal clerkLimit;

    /** The least any live due on the sheet can be; a settled claim's fixed due is kept as it was. */
    @Column(name = "floor", nullable = false, precision = 14, scale = 2)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The sheet's terms: a claim is due by its date plus this many days. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    /** The sheet's late percent: an overdue claim's live due gains it, of its floored due, per full 30 days overdue. */
    @Column(name = "late_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal latePct = new BigDecimal("0.00");

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

    /**
     * An open claim's live due (already floored; null stays null) with its late charge added: the late
     * percent of the floored due for every full 30 days its due-by (its date plus the terms) is before
     * today, rounded half to even to cents.
     */
    public BigDecimal withLateCharge(BigDecimal floored, LocalDate claimDate) {
        if (floored == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(claimDate.plusDays(terms), today);
        long periods = days > 0 ? days / 30 : 0;
        BigDecimal late = floored.multiply(latePct).multiply(BigDecimal.valueOf(periods))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_EVEN);
        return floored.add(late);
    }

    /**
     * A claim's due at its rate (null for none) as it counts: a correction's as it is, with no floor
     * and no late charge; any other claim's floored, with its late charge added.
     */
    public BigDecimal liveDue(BigDecimal due, LocalDate claimDate, boolean correction) {
        return correction ? due : withLateCharge(floored(due), claimDate);
    }

    /** A live due raised to the sheet's floor; null (no rate) stays null. */
    public BigDecimal floored(BigDecimal due) {
        return due == null ? null : due.max(floor);
    }

    /** Give out the sheet's next claim number. */
    public long takeClaimNumber() {
        return ++lastClaimNumber;
    }
}
