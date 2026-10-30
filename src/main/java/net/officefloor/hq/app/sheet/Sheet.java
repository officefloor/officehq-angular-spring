package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A sheet, keyed by the id a user types; created the first time it is loaded. Its settings that carry a
 * figure are dated, each kept as a {@link SheetSetting}; see {@link DatedSettings}.
 */
@Entity
@Table(name = "sheet")
public class Sheet {

    /** The floor a sheet starts with. */
    public static final BigDecimal DEFAULT_FLOOR = new BigDecimal("1.00");

    /** The terms a sheet starts with, in days. */
    public static final int DEFAULT_TERMS = 14;

    /** The late percent a sheet starts with. */
    public static final BigDecimal DEFAULT_LATE_PCT = new BigDecimal("0.00");

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /** The sheet's today: the date every date-dependent decision on the sheet uses. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The number given to the last claim opened on the sheet; 0 before any is opened. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The number of the last bulk settle that settled a claim on the sheet; 0 before any. */
    @Column(name = "last_settle_batch", nullable = false)
    private int lastSettleBatch;

    /** The date a claim's rounding turns on: half up before it, half to even on or after it; null: the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** From the sheet's today on this date, a claim settled is numbered within its period; null: off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** While set, the date a new claim takes when none is given, in place of the sheet's today; null: off. */
    @Column(name = "entry_date")
    private LocalDate entryDate;

    /** An open period whose to-date plus these days is before a new today is closed when the today is changed; null: off. */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** Whether a steward has archived the sheet: it stays visible and every change to it is refused. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    /** The sheet this one was cloned from; null when it was not cloned. */
    @Column(name = "cloned_from")
    private String clonedFrom;

    protected Sheet() {
    }

    public Sheet(String id, String createdBy, LocalDate today) {
        this.id = id;
        this.createdBy = createdBy;
        this.today = today;
    }

    /**
     * The sheet as copied to a clone under another id: its creator, today, policy date, period numbering from,
     * entry date and grace days; no claim number or settle batch taken yet, not archived, and cloned from this one.
     */
    public Sheet cloned(String id) {
        Sheet copy = new Sheet(id, createdBy, today);
        copy.policyDate = policyDate;
        copy.periodNumberingFrom = periodNumberingFrom;
        copy.entryDate = entryDate;
        copy.graceDays = graceDays;
        copy.clonedFrom = this.id;
        return copy;
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

    public LocalDate getPolicyDate() {
        return policyDate;
    }

    public void setPolicyDate(LocalDate policyDate) {
        this.policyDate = policyDate;
    }

    public LocalDate getPeriodNumberingFrom() {
        return periodNumberingFrom;
    }

    public void setPeriodNumberingFrom(LocalDate periodNumberingFrom) {
        this.periodNumberingFrom = periodNumberingFrom;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDate entryDate) {
        this.entryDate = entryDate;
    }

    public Integer getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(Integer graceDays) {
        this.graceDays = graceDays;
    }

    public int getLastSettleBatch() {
        return lastSettleBatch;
    }

    public String getClonedFrom() {
        return clonedFrom;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

}
