package net.officefloor.hq.app.sheet;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** A sheet, identified by the id a user types. Everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    /** The sheet's today: every date-dependent decision and default date on the sheet uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last plain claim number (1, 2, ...) given on the sheet before numbering went per fiscal year. */
    @Column(name = "last_claim_number", nullable = false)
    private long lastClaimNumber;

    /** The last claim number given on the sheet in each fiscal year (by the year it starts in); the
     *  next claim opened in a fiscal year takes the one after. */
    @ElementCollection
    @CollectionTable(name = "sheet_fiscal_claim_number", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "fiscal_year")
    @Column(name = "last_claim_number", nullable = false)
    private Map<Integer, Long> lastFiscalClaimNumbers = new HashMap<>();

    /** The last settle batch number given on the sheet (0 for none); the next bulk settle that
     *  settles a claim takes the one after. */
    @Column(name = "last_batch_number", nullable = false)
    private long lastBatchNumber;

    /** The policy date: a claim dated before it rounds half up, one on or after it half to even; null
     *  for the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** The period numbering from: while the sheet's today is on or after it, a claim opened is
     *  numbered within its period; null for off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** The grace days: when the sheet's today is changed, every open period whose to-date plus them
     *  is before the new today is closed; null for off. */
    @Column(name = "grace_days")
    private Integer graceDays;

    /** The escalation gap: reminding a claim whose previous reminder was at least this many days
     *  before the sheet's today escalates it; null for off. */
    @Column(name = "escalate_after")
    private Integer escalateAfter;

    /** The proof kinds, comma separated: a claim above the proof threshold needs a proof of each
     *  before it can be settled or approved; null for none. */
    @Column(name = "proof_kinds")
    private String proofKinds;

    /** The review SLA: a REVIEW claim whose review day plus this many days is before the sheet's
     *  today is late for review; null for none. */
    @Column(name = "review_sla")
    private Integer reviewSla;

    /** The pending SLA: a PENDING claim whose pending day plus this many days is before the sheet's
     *  today is late pending; null for none. */
    @Column(name = "pending_sla")
    private Integer pendingSla;

    /** The most OPEN or PENDING claims a person may have: opening one more is refused; null for
     *  none. */
    @Column(name = "max_open")
    private Integer maxOpen;

    /** The score thresholds, whole numbers: while both are set each person shows a band; null for
     *  blank. */
    @Column(name = "score_mid")
    private Integer scoreMid;

    /** The score log switch: while on, changing the sheet's today records each person's score on
     *  the day being left. */
    @Column(name = "score_log", nullable = false)
    private boolean scoreLog;

    @Column(name = "score_high")
    private Integer scoreHigh;

    /** The default grade, A, B or C: a person added by a quick-add, or by an import line with a blank
     *  grade, is given it; null for blank (A). */
    @Column(name = "default_grade")
    private String defaultGrade;

    /** The fiscal year start month, 1 to 12: a fiscal year runs twelve months from the first of it. */
    @Column(name = "fiscal_start_month", nullable = false)
    private int fiscalStartMonth = 1;

    /** The entry date: while set, a new claim given no date is dated it rather than the sheet's
     *  today; null for off. */
    @Column(name = "entry_date")
    private LocalDate entryDate;

    /** The pot: each member of a balanced pool shows a cut of it; null for none. */
    @Column(name = "pot", precision = 19, scale = 2)
    private BigDecimal pot;

    /** The alert threshold: a person whose due-total crosses above it is alerted; null for off. */
    @Column(name = "alert_threshold", precision = 19, scale = 2)
    private BigDecimal alertThreshold;

    /** The parent sheet's id: a grade with no rate in force on a date on this sheet takes the
     *  parent's; null for none. */
    @Column(name = "parent_id")
    private String parentId;

    /** The id of the sheet this one was cloned from: a grade with no rate in force on a date on this
     *  sheet nor on its parents takes that sheet's; null when it was not cloned. */
    @Column(name = "cloned_from")
    private String clonedFrom;

    /** Whether a steward has archived the sheet: every change to it is then refused. */
    @Column(name = "archived", nullable = false)
    private boolean archived;

    protected Sheet() {
    }

    public Sheet(String id, LocalDate today) {
        this.id = id;
        this.today = today;
    }

    public String getId() {
        return id;
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

    public Integer getMaxOpen() {
        return maxOpen;
    }

    public void setMaxOpen(Integer maxOpen) {
        this.maxOpen = maxOpen;
    }

    public Integer getScoreMid() {
        return scoreMid;
    }

    public void setScoreMid(Integer scoreMid) {
        this.scoreMid = scoreMid;
    }

    public boolean isScoreLog() {
        return scoreLog;
    }

    public void setScoreLog(boolean scoreLog) {
        this.scoreLog = scoreLog;
    }

    public Integer getScoreHigh() {
        return scoreHigh;
    }

    public void setScoreHigh(Integer scoreHigh) {
        this.scoreHigh = scoreHigh;
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

    public String getClonedFrom() {
        return clonedFrom;
    }

    public boolean isArchived() {
        return archived;
    }

    public void archive() {
        this.archived = true;
    }

    /** The fiscal year a date falls in, by the year it starts in, under the sheet's start month. */
    public int fiscalYearOf(LocalDate date) {
        return date.getMonthValue() >= fiscalStartMonth ? date.getYear() : date.getYear() - 1;
    }

    /** The last claim number given on the sheet in a fiscal year; 0 when there has been none. */
    public long getLastClaimNumber(int fiscalYear) {
        return lastFiscalClaimNumbers.getOrDefault(fiscalYear, 0L);
    }

    /** Take a claim number in a fiscal year following the last given in it anywhere in the sheet's tree. */
    public long takeClaimNumberAfter(int fiscalYear, long lastInTree) {
        long number = Math.max(getLastClaimNumber(fiscalYear), lastInTree) + 1;
        lastFiscalClaimNumbers.put(fiscalYear, number);
        return number;
    }

    /** The last settle batch number given on the sheet; null when there has been none. */
    public Long getLastBatchNumber() {
        return lastBatchNumber == 0 ? null : lastBatchNumber;
    }

    /** Take the sheet's next settle batch number. */
    public long nextBatchNumber() {
        return ++lastBatchNumber;
    }
}
