package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    /** Give out the sheet's next claim number: 1 for the first, then 2, and so on. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
