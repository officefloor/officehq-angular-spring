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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/** A sheet, kept by the id typed to load it. */
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

    /** The last number given to a claim opened on the sheet; 0 before the first. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The highest due a clerk may settle, with two decimals; none means no limit. */
    @Column(name = "clerk_limit", precision = 12, scale = 2)
    private BigDecimal clerkLimit;

    /** The least a claim's live due comes to, with two decimals. */
    @Column(name = "due_floor", nullable = false, precision = 12, scale = 2)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The days after its date a claim is due by. */
    @Column(name = "terms_days", nullable = false)
    private int terms = 14;

    /** The days after its date a claim of a person of a grade is due by, by grade; a grade not here uses the
     * sheet's terms. */
    @ElementCollection
    @CollectionTable(name = "sheet_grade_terms", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "grade")
    @Column(name = "terms_days", nullable = false)
    private Map<String, Integer> gradeTerms = new HashMap<>();

    /** The percent of its figure an overdue claim gains for every full 30 days it is overdue, with two decimals. */
    @Column(name = "late_pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal latePct = new BigDecimal("0.00");

    /** A claim dated before it rounds half up, one dated on or after it half to even; none means the sheet's
     * today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** While a date and the sheet's today is on or after it, a claim opened is numbered within its period; none
     * means off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** The most a person's due-total comes to, with two decimals; none means no cap. */
    @Column(name = "cap", precision = 12, scale = 2)
    private BigDecimal cap;

    protected Sheet() {}

    public Sheet(String id, String createdBy, Instant createdAt) {
        this.id = id;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.today = LocalDate.ofInstant(createdAt, ZoneId.systemDefault());
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

    /** The grade's own terms; none when it uses the sheet's. */
    public Integer getGradeTerms(String grade) {
        return gradeTerms.get(grade);
    }

    /** Set the grade's own terms; none makes it use the sheet's. */
    public void setGradeTerms(String grade, Integer terms) {
        if (terms == null) {
            gradeTerms.remove(grade);
        } else {
            gradeTerms.put(grade, terms);
        }
    }

    /** The terms a claim of a person of the grade is due by: the grade's own, else the sheet's. */
    public int termsFor(String grade) {
        return gradeTerms.getOrDefault(grade, terms);
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

    public BigDecimal getCap() {
        return cap;
    }

    public void setCap(BigDecimal cap) {
        this.cap = cap;
    }

    /** A person's due-total held to the cap: the cap when it is above it, else as it is; none (n/a) stays none. */
    public BigDecimal capped(BigDecimal dueTotal) {
        return dueTotal != null && cap != null && dueTotal.compareTo(cap) > 0 ? cap.setScale(2) : dueTotal;
    }

    /** Whether a claim opened now is numbered within its period: the period numbering date is set and the
     * sheet's today is on or after it. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** The date the claim rounding goes by: the policy date, or the sheet's today when there is none. */
    public LocalDate roundingDate() {
        return policyDate != null ? policyDate : today;
    }
}
