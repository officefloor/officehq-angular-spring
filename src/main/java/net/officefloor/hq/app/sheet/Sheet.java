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
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** A sheet: the unit everything else in the app is kept under. Created on its first load. */
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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** The sheet's today: every date-dependent decision and default date uses it, never the clock. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last claim number given on the sheet; the next claim opened takes the one after. */
    @Column(name = "last_claim_number", nullable = false)
    private int lastClaimNumber;

    /** The most a clerk may settle a claim's due at; null for no limit. */
    @Column(name = "clerk_limit", precision = 14, scale = 2)
    private BigDecimal clerkLimit;

    /** The least a claim's live due can be; 1.00 on a new sheet. */
    @Column(name = "due_floor", nullable = false, precision = 14, scale = 2)
    private BigDecimal floor = DEFAULT_FLOOR;

    /** The most a person's due-total can be; null for no cap. */
    @Column(name = "due_cap", precision = 14, scale = 2)
    private BigDecimal cap;

    /** The days after its date a claim is due by; 14 on a new sheet. */
    @Column(name = "terms", nullable = false)
    private int terms = DEFAULT_TERMS;

    /** The terms set for a grade, by grade; a grade without its own terms uses the sheet's terms. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "sheet_grade_terms", joinColumns = @JoinColumn(name = "sheet_id"))
    @MapKeyColumn(name = "grade")
    @Column(name = "terms", nullable = false)
    private Map<String, Integer> gradeTerms = new HashMap<>();

    /** The percent of its figure an overdue claim gains for every full 30 days overdue; 0.00 on a new sheet. */
    @Column(name = "late_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal latePct = DEFAULT_LATE_PCT;

    /** The date a claim's rounding turns on: half up before it, half to even on or after; null for the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** The date numbering per period starts: on or after it a claim opened is numbered within its period; null for off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

    /** The last number given in the sheet's X sequence, to claims opened under numbering per period in no period. */
    @Column(name = "last_unperiod_claim_number", nullable = false)
    private int lastUnperiodClaimNumber;

    protected Sheet() {
    }

    public Sheet(String id, String createdBy, Instant createdAt, LocalDate today) {
        this.id = id;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.today = today;
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

    public BigDecimal getCap() {
        return cap;
    }

    public void setCap(BigDecimal cap) {
        this.cap = cap;
    }

    /** A person's due-total held to the cap (n/a stays n/a). */
    public BigDecimal capped(BigDecimal dueTotal) {
        return dueTotal == null || cap == null ? dueTotal : dueTotal.min(cap);
    }

    public int getTerms() {
        return terms;
    }

    public void setTerms(int terms) {
        this.terms = terms;
    }

    /** The terms set for each grade that has its own, by grade. */
    public Map<String, Integer> getGradeTerms() {
        return new TreeMap<>(gradeTerms);
    }

    /** Set a grade's own terms. */
    public void setGradeTerms(String grade, int terms) {
        gradeTerms.put(grade, terms);
    }

    /** The terms for a grade: its own, else the sheet's. */
    public int termsFor(String grade) {
        return grade == null ? terms : gradeTerms.getOrDefault(grade, terms);
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

    /** Whether a claim opened on the sheet's today is numbered within its period. */
    public boolean numbersByPeriod() {
        return periodNumberingFrom != null && !today.isBefore(periodNumberingFrom);
    }

    /** Give out the next number in the sheet's X sequence, for a claim in no period. */
    public int nextUnperiodClaimNumber() {
        return ++lastUnperiodClaimNumber;
    }

    /** Give out the next claim number on the sheet. */
    public int nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
