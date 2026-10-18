package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** A sheet: the unit everything else in the app is kept under. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's "today": every date-dependent decision in the sheet uses it, never the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last claim number the sheet gave out; the next opened claim gets one more. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    protected Sheet() {}

    public Sheet(String id, String createdBy, Instant createdAt, LocalDate today) {
        this.id = id;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.today = today;
    }

    public String getId() { return id; }

    public String getCreatedBy() { return createdBy; }

    public Instant getCreatedAt() { return createdAt; }

    public LocalDate getToday() { return today; }

    public void setToday(LocalDate today) { this.today = today; }

    /** Give out the sheet's next claim number. */
    public int takeClaimNumber() { return ++lastClaimNumber; }
}
