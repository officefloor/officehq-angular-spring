package net.officefloor.hq.app.invoices;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** A payment received from a client on a date, allocated to its invoices. */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id")
    private Long clientId;

    private BigDecimal amount;

    @Column(name = "payment_date")
    private LocalDate date;

    /** The payment reference the client quoted, or null when none was. */
    private String reference;

    @ElementCollection
    @CollectionTable(name = "payment_allocation", joinColumns = @JoinColumn(name = "payment_id"))
    private List<PaymentAllocation> allocations = new ArrayList<>();

    protected Payment() {
    }

    /** A payment made wholly against one invoice, quoting the reference (null when none). */
    public static Payment against(Invoice invoice, BigDecimal amount, LocalDate date, String reference) {
        Payment payment = new Payment();
        payment.clientId = invoice.getClientId();
        payment.amount = amount;
        payment.date = date;
        payment.reference = reference;
        payment.allocations.add(new PaymentAllocation(invoice.getId(), amount));
        return payment;
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

    public String getReference() {
        return reference;
    }

    public List<PaymentAllocation> getAllocations() {
        return allocations;
    }

    /** How much of this payment goes to the invoice. */
    public BigDecimal amountTo(Long invoiceId) {
        return allocations.stream().filter(a -> a.getInvoiceId().equals(invoiceId))
                .map(PaymentAllocation::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
