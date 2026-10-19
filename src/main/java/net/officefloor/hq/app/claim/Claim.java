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

/** A claim a person makes on a sheet: an amount (negative for a correction), a date, a note, a status and, once opened, a number. */
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

    /** The sheet's number for the claim, given when it is opened and kept once voided; none while a draft. It is
     * the sheet-wide number, or with period numbering <period name>-<n>, or X-<n> in no period. */
    @Column(name = "claim_number")
    private String number;

    /** What was due on the claim when it was settled; none until then, or if its grade had no rate. */
    @Column(name = "settled_due", precision = 12, scale = 2)
    private BigDecimal settledDue;

    /** Who opened the claim; none while it is a draft. */
    @Column(name = "opened_by")
    private String openedBy;

    /** Whether the claim is bound to the figure it had at its person's last statement. */
    @Column(name = "stated", nullable = false)
    private boolean stated;

    /** The figure the claim was bound to at its person's last statement; none when it was n/a then. */
    @Column(name = "stated_due", precision = 12, scale = 2)
    private BigDecimal statedDue;

    /** The number of the settled claim of the same person a correction corrects; none for any other claim. */
    @Column(name = "corrects")
    private String corrects;

    protected Claim() {}

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
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

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
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

    public String getCorrects() {
        return corrects;
    }

    public void setCorrects(String corrects) {
        this.corrects = corrects;
    }

    /** Whether the claim is a correction: its amount is negative. */
    public boolean isCorrection() {
        return amount.signum() < 0;
    }

    public boolean isStated() {
        return stated;
    }

    public BigDecimal getStatedDue() {
        return statedDue;
    }

    /** Bind the claim to a figure (none for n/a). */
    public void bind(BigDecimal due) {
        this.stated = true;
        this.statedDue = due;
    }

    /** Leave the claim unbound, live again. */
    public void unbind() {
        this.stated = false;
        this.statedDue = null;
    }
}
