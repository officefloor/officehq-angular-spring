package net.officefloor.hq.app.instalment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** A scheduled part of an invoice: an amount due on a date. */
@Entity
@Table(name = "invoice_instalment")
public class Instalment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    protected Instalment() {
    }

    public Instalment(Long invoiceId, BigDecimal amount, LocalDate dueDate) {
        this.invoiceId = invoiceId;
        this.amount = amount.setScale(2);
        this.dueDate = dueDate;
    }

    public Long getId() {
        return id;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }
}
