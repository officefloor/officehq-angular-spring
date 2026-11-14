package net.officefloor.hq.app.sheet;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** A sheet: everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    /** The floor a sheet starts with. */
    public static final BigDecimal DEFAULT_FLOOR = new BigDecimal("1.00");

    /** The terms (in days) a sheet starts with. */
    public static final int DEFAULT_TERMS = 14;

    /** The late percent a sheet starts with. */
    public static final BigDecimal DEFAULT_LATE_PCT = new BigDecimal("0.00");

    @Id
    private String id;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /** The sheet's "today": every date decision and default date on the sheet uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last number given to a claim opened on the sheet; 0 before the first. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** A clerk settles a claim only when its due is at or below this; null when there is no limit. */
    @Column(name = "clerk_limit", precision = 15, scale = 2)
    private BigDecimal clerkLimit;

    /** A claim's live due is at least this. */
    @Column(name = "due_floor", nullable = false, precision = 15, scale = 2)
    private BigDecimal floor = DEFAULT_FLOOR;

    /** A claim is due by its date plus this many days. */
    @Column(name = "terms", nullable = false)
    private int terms = DEFAULT_TERMS;

    /** The terms set for a grade, in place of the sheet's terms; a grade not here uses them. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_grade_terms", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "grade")
    @Column(name = "terms", nullable = false)
    private Map<String, Integer> gradeTerms = new HashMap<>();

    /** An overdue claim's due gains this percent of its figure for every full 30 days it is overdue. */
    @Column(name = "late_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal latePct = DEFAULT_LATE_PCT;

    /** A claim dated before this rounds half up, one on or after it half to even; null when blank,
     *  when the sheet's today is the policy date. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** While set and the sheet's today is on or after it, a claim opened is numbered within its
     *  period; null when blank (off), when claims take the sheet-wide numbers. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** A person's due-total is at most this; null when there is no cap. */
    @Column(name = "due_cap", precision = 15, scale = 2)
    private BigDecimal cap;

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

    /** The terms set for a grade; null when it has none of its own (it uses the sheet's terms). */
    public Integer getGradeTerms(String grade) {
        return gradeTerms.get(grade);
    }

    /** Set a grade's own terms (null: none, it uses the sheet's terms). */
    public void setGradeTerms(String grade, Integer terms) {
        if (terms == null) {
            gradeTerms.remove(grade);
        } else {
            gradeTerms.put(grade, terms);
        }
    }

    /** The terms in force for a grade: its own, else the sheet's. */
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

    /** The policy date in force: the one set, else the sheet's today. */
    public LocalDate getPolicyDateInForce() {
        return policyDate != null ? policyDate : today;
    }

    public LocalDate getPeriodNumberingFrom() {
        return periodNumberingFrom;
    }

    public void setPeriodNumberingFrom(LocalDate periodNumberingFrom) {
        this.periodNumberingFrom = periodNumberingFrom;
    }

    /** Whether a claim opened now is numbered within its period: the setting is a date and the
     *  sheet's today is on or after it. */
    public boolean isPeriodNumberingOn() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    public BigDecimal getCap() {
        return cap;
    }

    public void setCap(BigDecimal cap) {
        this.cap = cap;
    }

    /** A due-total held to the cap (null, n/a, is not capped): the cap when the sum is above it. */
    public static BigDecimal capped(BigDecimal dueTotal, BigDecimal cap) {
        return dueTotal == null || cap == null ? dueTotal : dueTotal.min(cap);
    }

    /** Take the sheet's next claim number. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
