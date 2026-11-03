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

    /** Give out the next claim number on the sheet. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
