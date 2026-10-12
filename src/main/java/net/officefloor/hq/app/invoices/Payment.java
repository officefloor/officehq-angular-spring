package net.officefloor.hq.app.invoices;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import net.officefloor.hq.app.clients.Currency;

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

    /** The remittance text the client wrote on the payment, or null when none was. */
    private String remittance;

    /** The invoices whose purchase order numbers the parts of the remittance matched, in order; empty when none did. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "payment_match", joinColumns = @JoinColumn(name = "payment_id"))
    @OrderColumn(name = "position")
    private List<PaymentMatch> matches = new ArrayList<>();

    /** Whether the payment has been reversed (a bounced cheque): it then no longer counts. */
    private boolean reversed;

    /** The credit note whose rest this payment holds as credit; null for a payment from the client. */
    @Column(name = "credit_note")
    private Long creditNote;

    /** How much of the payment's unused credit has been refunded to the client; it is no longer credit. */
    private BigDecimal refunded = BigDecimal.ZERO;

    /** The currency the payment was paid in when not the client's own; null when it was the client's. */
    @Column(name = "paid_currency")
    private String paidCurrency;

    /** The amount paid in {@link #paidCurrency}; the amount is it converted into the client's currency. */
    @Column(name = "paid_amount")
    private BigDecimal paidAmount;

    /** How the payment was made: a bank transfer unless it was cash. */
    @Enumerated(EnumType.STRING)
    private PaymentMethod method = PaymentMethod.BANK;

    /** The part of the amount that is the card surcharge of a card payment: it pays nothing; 0 otherwise. */
    private BigDecimal surcharge = BigDecimal.ZERO;

    /** The part of the amount that is the conversion fee of a payment in another currency: it pays nothing; 0 otherwise. */
    @Column(name = "fx_fee")
    private BigDecimal fxFee = BigDecimal.ZERO;

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
        return deposit(clientId, amount, date, null);
    }

    /**
     * A deposit quoting the reference of one of the client's drafts (null when none): held for that
     * invoice while it is a draft.
     */
    public static Payment deposit(Long clientId, BigDecimal amount, LocalDate date, String reference) {
        Payment payment = new Payment();
        payment.clientId = clientId;
        payment.amount = amount;
        payment.date = date;
        payment.reference = reference;
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

    /**
     * Notes that the payment was paid as the amount in the currency (null for the client's own, which
     * is then nothing to note); its amount is that converted into the client's currency.
     */
    public Payment paidIn(Currency currency, BigDecimal paid, Currency clientCurrency) {
        if (currency != null && currency != clientCurrency) {
            paidCurrency = currency.name();
            paidAmount = paid;
        }
        return this;
    }

    /** Notes the remittance text (blank for none). */
    public Payment remitted(String remittance) {
        this.remittance = remittance == null || remittance.isBlank() ? null : remittance.trim();
        return this;
    }

    /** Notes the next invoice a part of the remittance matched and the similarity, rounded half up to three decimals. */
    public Payment matched(Long invoiceId, double score) {
        matches.add(new PaymentMatch(invoiceId, BigDecimal.valueOf(score).setScale(3, java.math.RoundingMode.HALF_UP)));
        return this;
    }

    public String getRemittance() {
        return remittance;
    }

    public List<PaymentMatch> getMatches() {
        return matches;
    }

    /** Notes how the payment was made (null for a bank transfer). */
    public Payment by(PaymentMethod method) {
        this.method = method == null ? PaymentMethod.BANK : method;
        return this;
    }

    /** Notes the part of the amount that is the card surcharge (null for none). */
    public Payment surcharged(BigDecimal surcharge) {
        this.surcharge = surcharge == null ? BigDecimal.ZERO : surcharge;
        return this;
    }

    /** Notes the part of the amount that is the conversion fee (null for none). */
    public Payment feeCharged(BigDecimal fee) {
        this.fxFee = fee == null ? BigDecimal.ZERO : fee;
        return this;
    }

    /** The part of the amount that is the conversion fee; 0 for a payment in the client's own currency. */
    public BigDecimal getFxFee() {
        return fxFee == null ? BigDecimal.ZERO : fxFee;
    }

    /** The part of the amount that is the card surcharge; 0 for a payment not made by card. */
    public BigDecimal getSurcharge() {
        return surcharge == null ? BigDecimal.ZERO : surcharge;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    /** The currency the payment was paid in: the given client's currency unless it was paid in another. */
    public Currency paidCurrency(Currency clientCurrency) {
        return paidCurrency == null ? clientCurrency : Currency.orUsd(paidCurrency);
    }

    /** The amount paid, in the currency it was paid in. */
    public BigDecimal paidAmount() {
        return paidAmount == null ? amount : paidAmount;
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
     * The part of this payment applied to no invoice, not refunded and not its card surcharge or conversion fee: credit
     * on the client's account; none once reversed.
     */
    public BigDecimal credit() {
        if (reversed) {
            return BigDecimal.ZERO;
        }
        return amount.subtract(getSurcharge()).subtract(getFxFee()).subtract(refunded).subtract(allocations.stream().map(PaymentAllocation::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Applies part of this payment's credit to the invoice on the day. */
    public void apply(Long invoiceId, BigDecimal part, LocalDate on) {
        allocations.add(new PaymentAllocation(invoiceId, part, on));
    }

    /**
     * Whether every part of this payment that goes to the invoice is credit used on it (applied on its
     * own day), none of it recorded against it.
     */
    public boolean onlyCreditUsedOn(Long invoiceId) {
        return allocations.stream().filter(a -> a.getInvoiceId().equals(invoiceId)).allMatch(a -> a.getDate() != null);
    }

    /** Takes back every part of this payment applied to the invoice, as unused credit again; how much that was. */
    public BigDecimal returnCredit(Long invoiceId) {
        BigDecimal returned = amountTo(invoiceId);
        allocations.removeIf(a -> a.getInvoiceId().equals(invoiceId));
        return returned;
    }

    /** How much of this payment goes to the invoice. */
    public BigDecimal amountTo(Long invoiceId) {
        return allocations.stream().filter(a -> a.getInvoiceId().equals(invoiceId))
                .map(PaymentAllocation::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
