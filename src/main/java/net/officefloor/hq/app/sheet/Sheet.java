package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A sheet: everything else in the app is kept per sheet. It starts empty when first loaded. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's today: every date-dependent decision on the sheet uses it, never the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last claim number the sheet gave out; 0 before any claim is opened. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The clerk limit: a clerk settles only claims whose due is at or below it; null for no limit. */
    @Column(name = "clerk_limit")
    private BigDecimal clerkLimit;

    /** The floor: a claim's live due is at least it. */
    @Column(name = "floor", nullable = false)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The terms: a claim is due by its date plus this many days. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    protected Sheet() {
    }

    public Sheet(String id, Instant createdAt, LocalDate today) {
        this.id = id;
        this.createdAt = createdAt;
        this.today = today;
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

    /** The clerk limit, with two decimals; null when there is none. */
    public BigDecimal getClerkLimit() {
        return clerkLimit;
    }

    public void setClerkLimit(BigDecimal clerkLimit) {
        this.clerkLimit = clerkLimit;
    }

    /** The floor, with two decimals. */
    public BigDecimal getFloor() {
        return floor;
    }

    public void setFloor(BigDecimal floor) {
        this.floor = floor;
    }

    /** The terms, in days. */
    public int getTerms() {
        return terms;
    }

    public void setTerms(int terms) {
        this.terms = terms;
    }

    /** Give out the sheet's next claim number: 1 for the first, then 2, and so on. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
