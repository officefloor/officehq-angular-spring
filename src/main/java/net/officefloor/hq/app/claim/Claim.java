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

/** A claim by a person on a sheet: an amount (two decimals), a date, a note, a status and, once opened,
 *  a number and who opened it. A negative amount is a correction, naming the number of the claim it corrects. */
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

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** A correction's: the number of the SETTLED claim of the same person it corrects; none otherwise. */
    @Column(name = "corrects_number")
    private String corrects;

    /** Given when the claim is opened; none while a draft or once voided. */
    @Column(name = "claim_number")
    private String number;

    /** Fixed when the claim is settled, if its grade had a rate then; none otherwise. */
    @Column(name = "settled_due")
    private BigDecimal settledDue;

    /** The user who opened the claim; none while a draft. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Bound by its person's last statement, which counted it: it shows and totals its stated due. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure it had when its person's last statement bound it; none when that was n/a, or unbound. */
    @Column(name = "stated_due")
    private BigDecimal statedDue;

    protected Claim() {
    }

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note, String corrects) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.note = note;
        this.corrects = corrects;
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

    /** A correction: its amount is negative. Its due has no floor and no late charge. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
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

    /** Bound by its person's last statement now: stated, and not PENDING (a PENDING claim shows and totals
     *  its live figure; rejected back to OPEN, it is bound again). */
    public boolean isBound() {
        return stated && status != ClaimStatus.PENDING;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    /** Bind the claim to a figure (none for n/a), or unbind it. */
    public void setStated(boolean stated, BigDecimal statedDue) {
        this.stated = stated;
        this.statedDue = stated ? statedDue : null;
    }
}
