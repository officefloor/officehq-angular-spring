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
 * A claim a person on a sheet makes: an amount (0.00 or more, two decimals), a date, a note and a
 * status (DRAFT when added).
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

    /** The sheet's number given to the claim when opened; null while DRAFT and once VOID. */
    @Column(name = "claim_number")
    private Integer number;

    /** The due fixed when the claim was settled; null otherwise, or when the grade had no rate then. */
    @Column(name = "settled_due", precision = 14, scale = 2)
    private BigDecimal settledDue;

    /** The user who opened the claim; null until it is opened. */
    @Column(name = "opened_by")
    private String openedBy;

    protected Claim() {
    }

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

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getNote() {
        return note;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public Integer getNumber() {
        return number;
    }

    public BigDecimal getSettledDue() {
        return settledDue;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    /** Record the user who opened the claim. */
    public void openedBy(String user) {
        this.openedBy = user;
    }

    /** Fix the claim's due as it is settled. */
    public void settleDue(BigDecimal due) {
        this.settledDue = due;
    }

    /** Move the claim to a new status; the caller checks the move is allowed. */
    public void become(ClaimStatus status) {
        this.status = status;
    }

    /** Give the claim its number (when opened), or clear it (when voided). */
    public void number(Integer number) {
        this.number = number;
    }

    /** Change the claim's amount, date and note. */
    public void change(BigDecimal amount, LocalDate date, String note) {
        this.amount = amount;
        this.date = date;
        this.note = note;
    }
}
