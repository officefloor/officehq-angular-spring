package net.officefloor.hq.app.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/** An amount a client has paid against an invoice on a given day. */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid_date", nullable = false)
    private LocalDate date;

    /** The lump payment this was split from; null when paid against the invoice on its own. */
    @Column(name = "client_payment_id")
    private Long clientPaymentId;

    /** The deposit application this was put toward the invoice from; null when it was not paid from deposits. */
    @Column(name = "deposit_application_id")
    private Long depositApplicationId;

    /** The share of the lump this was split from, in the lump's currency; null when not split from a lump. */
    @Column(name = "share_amount", precision = 12, scale = 2)
    private BigDecimal shareAmount;

    /** The currency the payment was received in, when not the invoice's; null when paid in the invoice's currency. */
    @Column(name = "paid_currency", length = 3)
    private String paidCurrency;

    /** What was received, in {@link #paidCurrency}; null when paid in the invoice's currency. */
    @Column(name = "paid_amount", precision = 12, scale = 2)
    private BigDecimal paidAmount;

    protected Payment() {
    }

    public Payment(Long invoiceId, BigDecimal amount, LocalDate date) {
        this.invoiceId = invoiceId;
        this.amount = amount.setScale(2);
        this.date = date;
    }

    public Payment(Long invoiceId, BigDecimal amount, LocalDate date, Long clientPaymentId) {
        this(invoiceId, amount, date);
        this.clientPaymentId = clientPaymentId;
    }

    /**
     * A share of a lump payment: {@code shareAmount} is taken out of the lump (in its currency) and {@code amount}
     * is what it settles on the invoice, converted into the invoice's currency.
     */
    public Payment(Long invoiceId, BigDecimal amount, LocalDate date, Long clientPaymentId, BigDecimal shareAmount) {
        this(invoiceId, amount, date, clientPaymentId);
        this.shareAmount = shareAmount.setScale(2);
    }

    /**
     * A payment received in another currency: {@code paidAmount} in {@code paidCurrency} was received and
     * {@code amount} is what it settles on the invoice, converted into the invoice's currency.
     */
    public static Payment inForeignCurrency(Long invoiceId, BigDecimal amount, LocalDate date, String paidCurrency,
            BigDecimal paidAmount) {
        Payment payment = new Payment(invoiceId, amount, date);
        payment.paidCurrency = paidCurrency;
        payment.paidAmount = paidAmount.setScale(2);
        return payment;
    }

    /** A share of a client's held deposits put toward an invoice. */
    public static Payment fromDeposits(Long invoiceId, BigDecimal amount, LocalDate date, Long depositApplicationId) {
        Payment payment = new Payment(invoiceId, amount, date);
        payment.depositApplicationId = depositApplicationId;
        return payment;
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

    public LocalDate getDate() {
        return date;
    }

    public Long getClientPaymentId() {
        return clientPaymentId;
    }

    public BigDecimal getShareAmount() {
        return shareAmount;
    }

    public String getPaidCurrency() {
        return paidCurrency;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public Long getDepositApplicationId() {
        return depositApplicationId;
    }
}
