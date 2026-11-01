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
import java.util.Map;
import java.util.TreeMap;

/** A sheet, keyed by the id a user types; everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's today: every date decision and default date on the sheet uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last claim number given on the sheet; the next claim opened takes the one after it. */
    @Column(name = "last_claim_number", nullable = false)
    private long lastClaimNumber;

    /** A clerk can settle a claim only when its due is at or below it; null means no limit. */
    @Column(name = "clerk_limit", precision = 14, scale = 2)
    private BigDecimal clerkLimit;

    /** A claim's live due is at least it; a settled or stated figure is not moved by it. */
    @Column(name = "due_floor", nullable = false, precision = 14, scale = 2)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The terms in days: a claim's due-by is its date plus them. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    /** The terms set for a grade, by grade; a grade without its own uses {@link #terms}. */
    @ElementCollection
    @CollectionTable(name = "sheet_grade_terms", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "grade")
    @Column(name = "terms", nullable = false)
    private Map<String, Integer> gradeTerms = new TreeMap<>();

    /** The late percent: an overdue claim's due gains it of its figure for every full 30 days overdue. */
    @Column(name = "late_pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal latePct = new BigDecimal("0.00");

    /** A claim dated before it rounds half up, one on or after it half to even; null means the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** While set and today is on or after it, a claim opened is numbered within its period; null means off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

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

    public BigDecimal getClerkLimit() { return clerkLimit; }

    public void setClerkLimit(BigDecimal clerkLimit) { this.clerkLimit = clerkLimit; }

    public BigDecimal getFloor() { return floor; }

    public void setFloor(BigDecimal floor) { this.floor = floor; }

    public int getTerms() { return terms; }

    public void setTerms(int terms) { this.terms = terms; }

    /** The terms set for the grade; null when it has none of its own. */
    public Integer getGradeTerms(String grade) { return gradeTerms.get(grade); }

    public void setGradeTerms(String grade, int terms) { gradeTerms.put(grade, terms); }

    /** The terms a claim of the grade is given: its own, else the sheet's. */
    public int termsFor(String grade) { return gradeTerms.getOrDefault(grade, terms); }

    public BigDecimal getLatePct() { return latePct; }

    public void setLatePct(BigDecimal latePct) { this.latePct = latePct; }

    public LocalDate getPolicyDate() { return policyDate; }

    public void setPolicyDate(LocalDate policyDate) { this.policyDate = policyDate; }

    public LocalDate getPeriodNumberingFrom() { return periodNumberingFrom; }

    public void setPeriodNumberingFrom(LocalDate periodNumberingFrom) { this.periodNumberingFrom = periodNumberingFrom; }

    /** Whether a claim opened now is numbered within its period rather than sheet-wide. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** Take the next claim number on the sheet. */
    public long nextClaimNumber() { return ++lastClaimNumber; }
}
