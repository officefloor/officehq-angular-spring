package net.officefloor.hq.app.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A lump sum a client paid on a given day, split across several of their invoices. It may also use up the client's
 * credit first (held deposits, then unused credit notes); whatever of the money received is not needed is kept as
 * credit for the client.
 */
@Entity
@Table(name = "client_payment")
public class ClientPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_date", nullable = false)
    private LocalDate date;

    /** Held deposits used up toward the invoices along with the money received. */
    @Column(name = "from_deposits", nullable = false, precision = 12, scale = 2)
    private BigDecimal fromDeposits = BigDecimal.ZERO.setScale(2);

    /** Unused credit notes used up toward the invoices along with the money received. */
    @Column(name = "from_credit_notes", nullable = false, precision = 12, scale = 2)
    private BigDecimal fromCreditNotes = BigDecimal.ZERO.setScale(2);

    /** Money received beyond what the invoices needed, kept as credit for the client. */
    @Column(name = "to_credit", nullable = false, precision = 12, scale = 2)
    private BigDecimal toCredit = BigDecimal.ZERO.setScale(2);

    protected ClientPayment() {
    }

    public ClientPayment(Long clientId, BigDecimal amount, LocalDate date) {
        this.clientId = clientId;
        this.amount = amount.setScale(2);
        this.date = date;
    }

    public ClientPayment(Long clientId, BigDecimal amount, LocalDate date, BigDecimal fromDeposits,
            BigDecimal fromCreditNotes, BigDecimal toCredit) {
        this(clientId, amount, date);
        this.fromDeposits = fromDeposits.setScale(2);
        this.fromCreditNotes = fromCreditNotes.setScale(2);
        this.toCredit = toCredit.setScale(2);
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

    public LocalDate getDate() {
        return date;
    }

    public BigDecimal getFromDeposits() {
        return fromDeposits;
    }

    public BigDecimal getFromCreditNotes() {
        return fromCreditNotes;
    }

    public BigDecimal getToCredit() {
        return toCredit;
    }
}
