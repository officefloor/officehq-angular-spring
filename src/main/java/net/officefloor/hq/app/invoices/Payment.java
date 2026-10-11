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

    /** Whether the payment has been reversed (a bounced cheque): it then no longer counts. */
    private boolean reversed;

    /** The credit note whose rest this payment holds as credit; null for a payment from the client. */
    @Column(name = "credit_note")
    private Long creditNote;

    /** How much of the payment's unused credit has been refunded to the client; it is no longer credit. */
    private BigDecimal refunded = BigDecimal.ZERO;

    @ElementCollection
    @CollectionTable(name = "payment_allocation", joinColumns = @JoinColumn(name = "payment_id"))
    private List<PaymentAllocation> allocations = new ArrayList<>();

    protected Payment() {
    }

    /**
     * A payment against one invoice, quoting the reference (null when none): the part applied to the
     * invoice, any rest of the amount held on the client's account as credit.
     */
    public static Payment against(Invoice invoice, BigDecimal amount, BigDecimal applied, LocalDate date,
            String reference) {
        Payment payment = new Payment();
        payment.clientId = invoice.getClientId();
        payment.amount = amount;
        payment.date = date;
        payment.reference = reference;
        payment.allocations.add(new PaymentAllocation(invoice.getId(), applied));
        return payment;
    }

    /**
     * A payment from the client spread over several of its invoices, quoting the reference (null when
     * none): each allocation is the part of the amount that goes to one invoice; any rest is held on
     * the client's account as credit.
     */
    public static Payment split(Long clientId, BigDecimal amount, LocalDate date, String reference,
            List<PaymentAllocation> allocations) {
        Payment payment = new Payment();
        payment.clientId = clientId;
        payment.amount = amount;
        payment.date = date;
        payment.reference = reference;
        payment.allocations.addAll(allocations);
        return payment;
    }

    /** A deposit: the whole amount held on the client's account as credit, applied to no invoice. */
    public static Payment deposit(Long clientId, BigDecimal amount, LocalDate date) {
        Payment payment = new Payment();
        payment.clientId = clientId;
        payment.amount = amount;
        payment.date = date;
        return payment;
    }

    /**
     * What is left of an issued credit note once its original has taken what it can: held on the
     * client's account as credit, dated the day the credit note was issued.
     */
    public static Payment fromCreditNote(Invoice note, BigDecimal amount) {
        Payment payment = deposit(note.getClientId(), amount, note.getIssueDate());
        payment.creditNote = note.getId();
        return payment;
    }

    /** The credit note whose rest this payment holds as credit; null for a payment from the client. */
    public Long getCreditNote() {
        return creditNote;
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

    public boolean isReversed() {
        return reversed;
    }

    /** Reverses the payment: it no longer counts on any invoice it was applied to. */
    public void reverse() {
        reversed = true;
    }

    public BigDecimal getRefunded() {
        return refunded;
    }

    /** Refunds part of this payment's unused credit to the client. */
    public void refund(BigDecimal part) {
        refunded = refunded.add(part);
    }

    public List<PaymentAllocation> getAllocations() {
        return allocations;
    }

    /**
     * The part of this payment applied to no invoice and not refunded: credit on the client's account;
     * none once reversed.
     */
    public BigDecimal credit() {
        if (reversed) {
            return BigDecimal.ZERO;
        }
        return amount.subtract(refunded).subtract(allocations.stream().map(PaymentAllocation::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Applies part of this payment's credit to the invoice on the day. */
    public void apply(Long invoiceId, BigDecimal part, LocalDate on) {
        allocations.add(new PaymentAllocation(invoiceId, part, on));
    }

    /** How much of this payment goes to the invoice. */
    public BigDecimal amountTo(Long invoiceId) {
        return allocations.stream().filter(a -> a.getInvoiceId().equals(invoiceId))
                .map(PaymentAllocation::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
