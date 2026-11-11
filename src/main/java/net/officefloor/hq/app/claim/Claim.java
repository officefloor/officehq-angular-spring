package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A claim made by a person on a sheet. */
@Entity
@Table(name = "claim")
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** Given when the claim is opened and kept once voided; null for a claim never opened. */
    @Column(name = "claim_number")
    private String number;

    /** The due fixed when the claim is settled; null otherwise, or when the grade had no rate then. */
    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    /** The user who opened the claim; null for a DRAFT. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Whether the claim is bound by its person's last statement. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The due the claim was bound to by its person's last statement; null when it had none (n/a). */
    @Column(name = "stated_due", precision = 14, scale = 2)
    private BigDecimal statedDue;

    /** The number of the SETTLED claim of the same person this claim corrects; null unless it is a correction. */
    @Column(name = "corrects")
    private String corrects;

    /** The day the claim is due by: fixed when it is added, from the terms of its person's grade on its date. */
    @Column(name = "due_by", nullable = false)
    private LocalDate dueBy;

    /** The date that decides how the claim's live figures round: its own date, or a correction's corrected claim's. */
    @Column(name = "rounding_date", nullable = false)
    private LocalDate roundingDate;

    /** Its place in its person's list: a claim with a later place is listed first. */
    @Column(name = "listed", nullable = false)
    private long listed;

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.roundingDate = date;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Long getPersonId() {
        return personId;
    }

    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public long getListed() {
        return listed;
    }

    public void setListed(long listed) {
        this.listed = listed;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCorrects() {
        return corrects;
    }

    public void setCorrects(String corrects) {
        this.corrects = corrects;
    }

    /** Whether the claim is a correction (a negative amount): its due has no floor and no late charge. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public LocalDate getDueBy() {
        return dueBy;
    }

    public void setDueBy(LocalDate dueBy) {
        this.dueBy = dueBy;
    }

    public LocalDate getRoundingDate() {
        return roundingDate;
    }

    public void setRoundingDate(LocalDate roundingDate) {
        this.roundingDate = roundingDate;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public void setStatus(ClaimStatus status) {
        this.status = status;
    }

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public BigDecimal getSettledDue() {
        return settledDue;
    }

    public void setSettledDue(BigDecimal settledDue) {
        this.settledDue = settledDue;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    public void setOpenedBy(String openedBy) {
        this.openedBy = openedBy;
    }

    public boolean isStated() {
        return stated;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    /**
     * Whether the claim's due follows its statement binding now: a PENDING claim is not bound while PENDING (it shows
     * its live figure), but keeps its binding for when it is rejected back to OPEN.
     */
    public boolean isBound() {
        return stated && status != ClaimStatus.PENDING;
    }

    /** The due the claim is bound to now; null when it is not bound, or bound with none (n/a). */
    public BigDecimal getBoundDue() {
        return isBound() ? statedDue : null;
    }

    /** Binds the claim to the due (null: n/a), or with {@code stated} false leaves it unbound and live. */
    public void setStated(boolean stated, BigDecimal statedDue) {
        this.stated = stated;
        this.statedDue = stated ? statedDue : null;
    }
}
