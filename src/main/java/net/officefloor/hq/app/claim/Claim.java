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

/**
 * A claim by a person on a sheet: an amount (0.00 or more, two decimals), a date, a note and a
 * status (DRAFT when added), and a number and who opened it once opened.
 */
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

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "claim_date", nullable = false)
    private LocalDate date;

    @Column(name = "note", nullable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ClaimStatus status = ClaimStatus.DRAFT;

    /** Given when the claim is opened; none while DRAFT, cleared when VOID. */
    @Column(name = "claim_number")
    private Long number;

    /** The user who opened the claim; none while DRAFT. */
    @Column(name = "opened_by", length = 100)
    private String openedBy;

    /** The due fixed when the claim was settled; none while not settled, or settled with no rate. */
    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    protected Claim() {}

    public Claim(String sheetId, Long personId, BigDecimal amount, LocalDate date, String note) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.amount = amount;
        this.date = date;
        this.note = note;
    }

    public Long getId() { return id; }

    public String getSheetId() { return sheetId; }

    public Long getPersonId() { return personId; }

    public BigDecimal getAmount() { return amount; }

    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDate getDate() { return date; }

    public void setDate(LocalDate date) { this.date = date; }

    public String getNote() { return note; }

    public void setNote(String note) { this.note = note; }

    public ClaimStatus getStatus() { return status; }

    public void setStatus(ClaimStatus status) { this.status = status; }

    public Long getNumber() { return number; }

    public void setNumber(Long number) { this.number = number; }

    public String getOpenedBy() { return openedBy; }

    public void setOpenedBy(String openedBy) { this.openedBy = openedBy; }

    public BigDecimal getSettledDue() { return settledDue; }

    public void setSettledDue(BigDecimal settledDue) { this.settledDue = settledDue; }
}
