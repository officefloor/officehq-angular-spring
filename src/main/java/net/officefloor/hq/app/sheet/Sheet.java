package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A sheet, identified by the id a user types; everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /** The sheet's today: every date-dependent decision and default date uses it, never the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last plain claim number given on the sheet, before numbering restarted each fiscal year
     *  ({@link FiscalClaimNumber}); no longer moved on. */
    @Column(name = "last_claim_number", insertable = false, updatable = false)
    private int lastClaimNumber;

    /** The number of the last settle batch on the sheet (0 when none); only ever moved on by
     *  {@link SheetRepository#advanceSettleBatch}. */
    @Column(name = "last_settle_batch", insertable = false, updatable = false)
    private int lastSettleBatch;

    /** The policy date: a claim dated before it rounds half up, one on or after it half to even; null
     *  (blank) takes the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** Period numbering from: while it is set and the sheet's today is on or after it, a claim opened
     *  is numbered within its period; null (blank) is off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** The last X-number given to a claim in no period; only ever moved on by
     *  {@link SheetRepository#advanceUnperiodNumber}. */
    @Column(name = "last_unperiod_number", insertable = false, updatable = false)
    private int lastUnperiodNumber;

    /** Grace days: when the sheet's today is changed, an open period whose to-date plus them is
     *  before the new today is closed; null (blank) is off. */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** The escalation gap in days: a reminder at least this many days after the claim's previous one
     *  escalates it; null when blank (off). */
    @Column(name = "escalate_after")
    private Integer escalateAfter;

    /** The maximum of open (OPEN or PENDING) claims per person; null when blank (none). */
    @Column(name = "max_open")
    private Integer maxOpen;

    /** The review SLA in days: a claim in review whose review day plus them is before the sheet's
     *  today is late; null when blank (none). */
    @Column(name = "review_sla")
    private Integer reviewSla;

    /** The pending SLA in days: a pending claim whose pending day plus them is before the sheet's
     *  today is late; null when blank (none). */
    @Column(name = "pending_sla")
    private Integer pendingSla;

    /** The mid score threshold: a person whose score is at or above it is MID (or HIGH); null when
     *  blank (no bands). */
    @Column(name = "score_mid")
    private Integer scoreMid;

    /** The high score threshold: a person whose score is at or above it is HIGH; null when blank
     *  (no bands). */
    @Column(name = "score_high")
    private Integer scoreHigh;

    /** The score log switch: while on, changing the sheet's today records each person's score on
     *  the day being left; false (blank) is off. */
    @Column(name = "score_log")
    private boolean scoreLog;

    /** Entry date: while it is set, the default date of a new claim in place of the sheet's today;
     *  null (blank) is off. */
    @Column(name = "entry_date")
    private LocalDate entryDate;

    /** The pot: what a balanced pool's members cut by their shares; null (blank) is none. */
    @Column(name = "pot")
    private BigDecimal pot;

    /** The alert threshold: a person whose due-total goes from at or below it to above it is
     *  alerted; null (blank) is off. Not dated. */
    @Column(name = "alert_threshold")
    private BigDecimal alertThreshold;

    /** The fiscal year start month, 1 to 12: a fiscal year runs from the first day of it, and is
     *  named by the calendar year that day falls in. Not dated. */
    @Column(name = "fiscal_start_month", nullable = false)
    private int fiscalStartMonth = 1;

    /** The parent sheet's id: a grade with no rate in force on a date on this sheet takes the
     *  parent's rate for that grade and date, then its parent's, up to three levels up; null (blank)
     *  is none. Not dated. */
    @Column(name = "parent_id")
    private String parentId;

    /** The id of the sheet this one was cloned from: a grade with no rate in force on a date on this
     *  sheet nor on its parents takes that sheet's rate (see SheetRepository#rateSources); null when
     *  it was not cloned. Not dated. */
    @Column(name = "source_id")
    private String sourceId;

    /** The sheet's proof kinds, comma separated: a claim above the proof threshold needs at least one
     *  proof of each before it is settled or approved; null (blank) is none. Not dated. */
    @Column(name = "proof_kinds")
    private String proofKinds;

    /** The sheet's default grade: the grade a person added by the quick-add bar, or by an import
     *  line with a blank grade, is given; null (blank) is A. Not dated. */
    @Column(name = "default_grade")
    private String defaultGrade;

    /** Whether a steward has archived the sheet: it stays visible and refuses every change. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

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

    public Integer getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(Integer graceDays) {
        this.graceDays = graceDays;
    }

    public Integer getEscalateAfter() {
        return escalateAfter;
    }

    public void setEscalateAfter(Integer escalateAfter) {
        this.escalateAfter = escalateAfter;
    }

    public Integer getMaxOpen() {
        return maxOpen;
    }

    public void setMaxOpen(Integer maxOpen) {
        this.maxOpen = maxOpen;
    }

    public Integer getReviewSla() {
        return reviewSla;
    }

    public void setReviewSla(Integer reviewSla) {
        this.reviewSla = reviewSla;
    }

    public Integer getPendingSla() {
        return pendingSla;
    }

    public void setPendingSla(Integer pendingSla) {
        this.pendingSla = pendingSla;
    }

    public Integer getScoreMid() {
        return scoreMid;
    }

    public void setScoreMid(Integer scoreMid) {
        this.scoreMid = scoreMid;
    }

    public Integer getScoreHigh() {
        return scoreHigh;
    }

    public void setScoreHigh(Integer scoreHigh) {
        this.scoreHigh = scoreHigh;
    }

    public boolean isScoreLog() {
        return scoreLog;
    }

    public void setScoreLog(boolean scoreLog) {
        this.scoreLog = scoreLog;
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

    public int getFiscalStartMonth() {
        return fiscalStartMonth;
    }

    public void setFiscalStartMonth(int fiscalStartMonth) {
        this.fiscalStartMonth = fiscalStartMonth;
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

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getProofKinds() {
        return proofKinds;
    }

    public void setProofKinds(String proofKinds) {
        this.proofKinds = proofKinds;
    }

    public String getDefaultGrade() {
        return defaultGrade;
    }

    public void setDefaultGrade(String defaultGrade) {
        this.defaultGrade = defaultGrade;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }
}
