package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A sheet: the unit everything else in the app is kept under. Created the first time it is loaded. */
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

    /** The date that decides how a claim's live figures round: half up when the claim is dated before
     * it, half to even on or after it; null when it is the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** The date from which a claim opened is numbered within its period; null when that is off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** How many days after its to-date an open period is closed when the sheet's today passes them; null when that is off. */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** How many days after a claim's previous reminder reminding it escalates it; null when that is off. */
    @Column(name = "escalate_after")
    private Integer escalateAfter;

    /** The proof kinds a claim above the proof threshold needs a proof of each of before it is settled or
     * approved, comma separated; null when none are listed. */
    @Column(name = "proof_kinds")
    private String proofKinds;

    /** How many days a claim may stay in REVIEW from its review day before it is late; null when there is none. */
    @Column(name = "review_sla")
    private Integer reviewSla;

    /** The month (1 to 12) the sheet's fiscal year starts in. */
    @Column(name = "fiscal_start_month", nullable = false)
    private int fiscalStartMonth = 1;

    /** The date a claim added or edited without a date is dated; null when that is off (the sheet's today). */
    @Column(name = "entry_date")
    private LocalDate entryDate;

    /** The pot shared out among the members of each balanced pool by their shares; null when there is none. */
    @Column(name = "pot")
    private BigDecimal pot;

    /** The threshold a person's due-total going above writes an ALERT line; null when it is off. */
    @Column(name = "alert_threshold")
    private BigDecimal alertThreshold;

    /** The id of the sheet's parent, whose rates are used where this sheet has none in force; null when
     * there is none. */
    @Column(name = "parent_id")
    private String parentId;

    /** Whether the sheet is archived: everything on it stays visible and every change is refused. */
    /** The id of the sheet this one was cloned from, whose rates are used where neither this sheet nor its
     * parents have one in force; null when it was not cloned. */
    @Column(name = "source_id")
    private String sourceId;

    @Column(name = "archived", nullable = false)
    private boolean archived;

    /** The number of the sheet's last bulk settle that settled a claim (its batches are numbered 1, 2, ...);
     * null while it has had none. */
    @Column(name = "last_settle_batch")
    private Integer lastSettleBatch;

    protected Sheet() {
    }

    public Sheet(String id, String createdBy, Instant createdAt, LocalDate today) {
        this.id = id;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.today = today;
    }

    /** A clone of the sheet under the id {@code id}, started by {@code createdBy} at {@code createdAt}: its
     * today and its settings (policy date, period numbering, grace days, escalation gap, proof kinds, fiscal start month, entry date, pot, alert threshold and parent) come
     * with it, and this sheet is its source; it is not archived and has had no settle batch. */
    public Sheet clonedTo(String id, String createdBy, Instant createdAt) {
        Sheet copy = new Sheet(id, createdBy, createdAt, today);
        copy.policyDate = policyDate;
        copy.periodNumberingFrom = periodNumberingFrom;
        copy.graceDays = graceDays;
        copy.escalateAfter = escalateAfter;
        copy.proofKinds = proofKinds;
        copy.reviewSla = reviewSla;
        copy.fiscalStartMonth = fiscalStartMonth;
        copy.entryDate = entryDate;
        copy.pot = pot;
        copy.alertThreshold = alertThreshold;
        copy.parentId = parentId;
        copy.sourceId = this.id;
        return copy;
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

    public LocalDate getPolicyDate() {
        return policyDate;
    }

    public void setPolicyDate(LocalDate policyDate) {
        this.policyDate = policyDate;
    }

    /** The policy date in force: the one set, or the sheet's today when none is. */
    public LocalDate policyDateInForce() {
        return policyDate == null ? today : policyDate;
    }

    public LocalDate getPeriodNumberingFrom() {
        return periodNumberingFrom;
    }

    public void setPeriodNumberingFrom(LocalDate periodNumberingFrom) {
        this.periodNumberingFrom = periodNumberingFrom;
    }

    /** Whether a claim opened now is numbered within its period: the setting is a date and the sheet's
     * today is on or after it. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    public Integer getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(Integer graceDays) {
        this.graceDays = graceDays;
    }

    public String getProofKinds() {
        return proofKinds;
    }

    public void setProofKinds(String proofKinds) {
        this.proofKinds = proofKinds;
    }

    public Integer getEscalateAfter() {
        return escalateAfter;
    }

    public void setEscalateAfter(Integer escalateAfter) {
        this.escalateAfter = escalateAfter;
    }

    public Integer getReviewSla() {
        return reviewSla;
    }

    public void setReviewSla(Integer reviewSla) {
        this.reviewSla = reviewSla;
    }

    public int getFiscalStartMonth() {
        return fiscalStartMonth;
    }

    public void setFiscalStartMonth(int fiscalStartMonth) {
        this.fiscalStartMonth = fiscalStartMonth;
    }

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDate entryDate) {
        this.entryDate = entryDate;
    }

    public BigDecimal getPot() {
        return pot;
    }

    public void setPot(BigDecimal pot) {
        this.pot = pot;
    }

    public BigDecimal getAlertThreshold() {
        return alertThreshold;
    }

    public void setAlertThreshold(BigDecimal alertThreshold) {
        this.alertThreshold = alertThreshold;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getSourceId() {
        return sourceId;
    }

    /** The date a claim left without a date is dated: the entry date when it is set, else the sheet's today. */
    public LocalDate entryDateInForce() {
        return entryDate == null ? today : entryDate;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public Integer getLastSettleBatch() {
        return lastSettleBatch;
    }

    /** Start the sheet's next settle batch, returning its number. */
    public int nextSettleBatch() {
        lastSettleBatch = lastSettleBatch == null ? 1 : lastSettleBatch + 1;
        return lastSettleBatch;
    }
}
