package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A sheet, identified by the id a user types. Everything else in the app is kept per sheet. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    /** The sheet's today: every date-dependent decision and default date on the sheet uses it. */
    @Column(name = "today", nullable = false)
    private LocalDate today;

    /** The last claim number given on the sheet; the next claim opened takes the one after. */
    @Column(name = "last_claim_number", nullable = false)
    private long lastClaimNumber;

    /** The clerk limit: a clerk settles a claim only when its due is at or below it; null for none. */
    @Column(name = "clerk_limit")
    private BigDecimal clerkLimit;

    /** The floor: a claim's live due is at least it. */
    @Column(name = "due_floor", nullable = false)
    private BigDecimal floor = new BigDecimal("1.00");

    /** The terms in days: a claim is due by its date plus them. */
    @Column(name = "terms", nullable = false)
    private int terms = 14;

    /** The late percent: an overdue claim's due gains it of its figure for every full 30 days overdue. */
    @Column(name = "late_pct", nullable = false, precision = 12, scale = 2)
    private BigDecimal latePct = new BigDecimal("0.00");

    /** The policy date: a claim dated before it rounds half up, one on or after it half to even; null
     *  for the sheet's today. */
    @Column(name = "policy_date")
    private LocalDate policyDate;

    /** The period numbering from: while the sheet's today is on or after it, a claim opened is
     *  numbered within its period; null for off. */
    @Column(name = "period_numbering_from")
    private LocalDate periodNumberingFrom;

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

    /** Take the sheet's next claim number. */
    public long nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
