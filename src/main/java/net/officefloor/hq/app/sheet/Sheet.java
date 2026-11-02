package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    /** The last claim number given on the sheet; only ever moved on by {@link SheetRepository#nextClaimNumber}. */
    @Column(name = "last_claim_number", insertable = false, updatable = false)
    private int lastClaimNumber;

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

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }
}
