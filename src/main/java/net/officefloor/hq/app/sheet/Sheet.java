package net.officefloor.hq.app.sheet;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

    /** The entry date: while set, a new claim given no date is dated it rather than the sheet's
     *  today; null for off. */
    @Column(name = "entry_date")
    private LocalDate entryDate;

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

    public LocalDate getEntryDate() {
        return entryDate;
    }

    public void setEntryDate(LocalDate entryDate) {
        this.entryDate = entryDate;
    }

    public boolean isArchived() {
        return archived;
    }

    public void archive() {
        this.archived = true;
    }

    /** Take the sheet's next claim number. */
    public long nextClaimNumber() {
        return ++lastClaimNumber;
    }
}
