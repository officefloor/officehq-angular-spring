package net.officefloor.hq.app.refund;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Unused credit paid back to a client on a given day, split between what came out of their held
 * deposits and what came out of their unused credit notes.
 */
@Entity
@Table(name = "refund")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "from_deposits", nullable = false, precision = 12, scale = 2)
    private BigDecimal fromDeposits;

    @Column(name = "from_credit_notes", nullable = false, precision = 12, scale = 2)
    private BigDecimal fromCreditNotes;

    @Column(name = "refunded_on", nullable = false)
    private LocalDate date;

    @Column(length = 500)
    private String note;

    protected Refund() {
    }

    public Refund(Long clientId, BigDecimal fromDeposits, BigDecimal fromCreditNotes, LocalDate date, String note) {
        this.clientId = clientId;
        this.fromDeposits = fromDeposits.setScale(2);
        this.fromCreditNotes = fromCreditNotes.setScale(2);
        this.amount = this.fromDeposits.add(this.fromCreditNotes);
        this.date = date;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public Long getClientId() {
        return clientId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getFromDeposits() {
        return fromDeposits;
    }

    public BigDecimal getFromCreditNotes() {
        return fromCreditNotes;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getNote() {
        return note;
    }
}
