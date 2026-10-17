package net.officefloor.hq.app.sheet;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/** A sheet, identified by the id a user types to load it. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /** The sheet's "today": every date decision and default date in the sheet uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last number given to a claim opened on the sheet; 0 before the first. */
    @Column(name = "last_claim_number", nullable = false)
    private long lastClaimNumber;

    /** The most due a clerk can settle a claim for; null for no limit. */
    @Column(name = "clerk_limit", precision = 14, scale = 2)
    private BigDecimal clerkLimit;

    /** The least any live due on the sheet can be; a settled claim's fixed due is kept as it was. */
    @Column(name = "floor", nullable = false, precision = 14, scale = 2)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The sheet's terms: a claim is due by its date plus this many days. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    /** Each grade's own terms, by grade; a grade with none uses the sheet's terms. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_grade_terms", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "grade")
    @Column(name = "terms", nullable = false)
    private Map<String, Integer> gradeTerms = new HashMap<>();

    /** The sheet's late percent: an overdue claim's live due gains it, of its floored due, per full 30 days overdue. */
    @Column(name = "late_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal latePct = new BigDecimal("0.00");

    /** The sheet's policy date: it decides how a claim's live figures round; null for the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** The sheet's period numbering from: from this date (by the sheet's today) claims opened are numbered within their period; null for off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

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

    /** The grade's own terms; null when it has none (it uses the sheet's terms). */
    public Integer getGradeTerms(String grade) {
        return gradeTerms.get(grade);
    }

    /** Set the grade's own terms; null removes them, so it uses the sheet's terms. */
    public void setGradeTerms(String grade, Integer days) {
        if (days == null) {
            gradeTerms.remove(grade);
        } else {
            gradeTerms.put(grade, days);
        }
    }

    /** The due-by of a claim of this date for a person of this grade now: its date plus the grade's terms, or the sheet's. */
    public LocalDate dueBy(String grade, LocalDate claimDate) {
        Integer own = gradeTerms.get(grade);
        return claimDate.plusDays(own != null ? own : terms);
    }

    public BigDecimal getLatePct() {
        return latePct;
    }

    public void setLatePct(BigDecimal latePct) {
        this.latePct = latePct;
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

    /** Whether a claim opened now is numbered within its period: the period numbering from is set and today is on or after it. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /**
     * How a claim's live figures round: half up when it is dated before the policy date (the sheet's
     * today when none is set), half to even when dated on or after it.
     */
    public RoundingMode roundingFor(LocalDate claimDate) {
        LocalDate policy = policyDate != null ? policyDate : today;
        return claimDate.isBefore(policy) ? RoundingMode.HALF_UP : RoundingMode.HALF_EVEN;
    }

    /** An amount's due at a rate (a percent), rounded to cents as the claim's date decides. */
    public BigDecimal dueAt(BigDecimal amount, BigDecimal rate, LocalDate claimDate) {
        return amount.multiply(rate).divide(BigDecimal.valueOf(100), 2, roundingFor(claimDate));
    }

    /**
     * An open claim's live due (already floored; null stays null) with its late charge added: the late
     * percent of the floored due for every full 30 days its due-by (fixed when it was created) is before
     * today, rounded to cents as the claim's date decides.
     */
    public BigDecimal withLateCharge(BigDecimal floored, LocalDate claimDate, LocalDate dueBy) {
        if (floored == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(dueBy, today);
        long periods = days > 0 ? days / 30 : 0;
        BigDecimal late = floored.multiply(latePct).multiply(BigDecimal.valueOf(periods))
                .divide(BigDecimal.valueOf(100), 2, roundingFor(claimDate));
        return floored.add(late);
    }

    /**
     * A claim's due at its rate (null for none) as it counts: a correction's as it is, with no floor
     * and no late charge; any other claim's floored, with its late charge added.
     */
    public BigDecimal liveDue(BigDecimal due, LocalDate claimDate, LocalDate dueBy, boolean correction) {
        return correction ? due : withLateCharge(floored(due), claimDate, dueBy);
    }

    /** A live due raised to the sheet's floor; null (no rate) stays null. */
    public BigDecimal floored(BigDecimal due) {
        return due == null ? null : due.max(floor);
    }

    /** Give out the sheet's next claim number. */
    public long takeClaimNumber() {
        return ++lastClaimNumber;
    }
}
