package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A sheet, keyed by the id a user types; created the first time it is loaded. */
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

    /** The most due a clerk may settle on a claim; null when there is no limit. */
    @Column(name = "clerk_limit", precision = 14, scale = 2)
    private BigDecimal clerkLimit;

    /** The least live due a claim has; 1.00 until set. */
    @Column(name = "due_floor", nullable = false, precision = 14, scale = 2)
    private BigDecimal floor = DEFAULT_FLOOR;

    /** The days a claim has to be paid by, from its date; 14 until set. */
    @Column(name = "terms", nullable = false)
    private int terms = DEFAULT_TERMS;

    /** The days a claim of grade A, B or C has to be paid by, from its date; null: the sheet's terms. */
    @Column(name = "terms_a")
    private Integer termsA;

    @Column(name = "terms_b")
    private Integer termsB;

    @Column(name = "terms_c")
    private Integer termsC;

    /** The percent of its figure an overdue claim's due gains for every full 30 days overdue; 0.00 until set. */
    @Column(name = "late_pct", nullable = false, precision = 14, scale = 2)
    private BigDecimal latePct = DEFAULT_LATE_PCT;

    /** The date a claim's rounding turns on: half up before it, half to even on or after it; null: the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** From the sheet's today on this date, a claim opened is numbered within its period; null: off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** The most a person's due-total comes to; null when there is no cap. */
    @Column(name = "due_cap", precision = 14, scale = 2)
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

    /** The grade's own terms; null when it has none (it uses the sheet's terms). */
    public Integer getGradeTerms(String grade) {
        return switch (grade) {
            case "A" -> termsA;
            case "B" -> termsB;
            case "C" -> termsC;
            default -> null;
        };
    }

    /** Set the grade's own terms; null removes them (it uses the sheet's terms). */
    public void setGradeTerms(String grade, Integer days) {
        switch (grade) {
            case "A" -> termsA = days;
            case "B" -> termsB = days;
            case "C" -> termsC = days;
            default -> throw new IllegalArgumentException("No such grade " + grade);
        }
    }

    /** The terms in force for a claim of the grade: its own, or the sheet's when it has none. */
    public int termsFor(String grade) {
        Integer own = grade == null ? null : getGradeTerms(grade);
        return own != null ? own : terms;
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

    public BigDecimal getLatePct() {
        return latePct;
    }

    public void setLatePct(BigDecimal latePct) {
        this.latePct = latePct;
    }
}
