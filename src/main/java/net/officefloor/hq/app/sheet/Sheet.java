package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** A sheet, kept by the id typed to load it. */
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

    /** The last number given to a claim opened on the sheet; 0 before the first. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The highest due a clerk may settle, with two decimals; none means no limit. */
    @Column(name = "clerk_limit", precision = 12, scale = 2)
    private BigDecimal clerkLimit;

    /** The least a claim's live due comes to, with two decimals. */
    @Column(name = "due_floor", nullable = false, precision = 12, scale = 2)
    private BigDecimal floor = new BigDecimal("1.00");

    protected Sheet() {}

    public Sheet(String id, String createdBy, Instant createdAt) {
        this.id = id;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.today = LocalDate.ofInstant(createdAt, ZoneId.systemDefault());
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
}
