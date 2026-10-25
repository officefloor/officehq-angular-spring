package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    /** The sheet's own "today": every date-dependent decision and default date uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** How many claim numbers the sheet has given out; the next opened claim takes one more. */
    @Column(name = "claim_numbers_used", nullable = false)
    private int claimNumbersUsed;

    /** How many bulk settle batches the sheet has had; the last batch has this number, none when 0. */
    @Column(name = "settle_batches_used", nullable = false)
    private int settleBatchesUsed;

    /** An archived sheet stays visible, and every change on it is refused. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

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

    /** The number of the sheet's last settle batch, or null when it has had none. */
    public Integer getLastSettleBatch() {
        return settleBatchesUsed == 0 ? null : settleBatchesUsed;
    }

    public boolean isArchived() {
        return archived;
    }

    public void archive() {
        this.archived = true;
    }
}
