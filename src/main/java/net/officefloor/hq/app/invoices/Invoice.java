package net.officefloor.hq.app.invoices;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** An invoice raised for a client, made of lines. */
@Entity
@Table(name = "invoice")
public class Invoice {

    /** Payments that leave less than this still due have the rest written off. */
    public static final BigDecimal WRITE_OFF_LIMIT = new BigDecimal("1.00");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_id")
    private Long clientId;

    @Enumerated(EnumType.STRING)
    private InvoiceStatus status;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "discount_pct")
    private BigDecimal discountPct = BigDecimal.ZERO;

    @Column(name = "discount_amount")
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "discount_cap")
    private BigDecimal discountCap;

    @Column(name = "discount_reason")
    private String discountReason;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "invoice_id", nullable = false)
    @OrderBy("id")
    private List<InvoiceLine> lines = new ArrayList<>();

    protected Invoice() {
    }

    /** A new, empty draft for a client, issued and due on the given dates. */
    public static Invoice draft(Long clientId, LocalDate issueDate, LocalDate dueDate) {
        Invoice invoice = new Invoice();
        invoice.clientId = clientId;
        invoice.status = InvoiceStatus.DRAFT;
        invoice.issueDate = issueDate;
        invoice.dueDate = dueDate;
        return invoice;
    }

    /**
     * A new draft for the same client with copies of this invoice's lines and the same invoice
     * discount, issued and due on the given dates. Payments stay with this invoice.
     */
    public Invoice duplicate(LocalDate issueDate, LocalDate dueDate) {
        Invoice copy = draft(clientId, issueDate, dueDate);
        copy.discountPct = discountPct;
        copy.discountAmount = discountAmount;
        copy.discountCap = discountCap;
        copy.discountReason = discountReason;
        lines.forEach(line -> copy.lines.add(line.copy()));
        return copy;
    }

    public Long getId() {
        return id;
    }

    public Long getClientId() {
        return clientId;
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    /** The percentage taken off the subtotal, 0 to 100 with up to two decimals. */
    public BigDecimal getDiscountPct() {
        return discountPct;
    }

    /** The fixed amount taken off the subtotal, to the cent; 0 when the discount is a percentage. */
    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    /** The most taken off the invoice in all, line discounts included, to the cent; null when there is no cap. */
    public BigDecimal getDiscountCap() {
        return discountCap;
    }

    /** Why the discount was given; null when no reason was given. */
    public String getDiscountReason() {
        return discountReason;
    }

    /** Sets why the discount was given; blank is no reason. */
    public void setDiscountReason(String discountReason) {
        this.discountReason = discountReason == null || discountReason.isBlank() ? null : discountReason.strip();
    }

    /** Whether the discount takes off more than a fifth of the subtotal, after any cap, and so needs a reason. */
    public boolean discountNeedsReason() {
        return discount().multiply(BigDecimal.valueOf(5)).compareTo(subtotal()) > 0;
    }

    /** Whether the discount needs a reason but has none. */
    public boolean discountLacksReason() {
        return discountNeedsReason() && discountReason == null;
    }

    /** Sets the percentage taken off the subtotal, clearing any fixed amount; it has already been checked. */
    public void setDiscountPct(BigDecimal discountPct) {
        this.discountPct = discountPct;
        this.discountAmount = BigDecimal.ZERO;
    }

    /** Sets the most taken off the invoice in all, or null for no cap; it has already been checked as money. */
    public void setDiscountCap(BigDecimal discountCap) {
        this.discountCap = discountCap;
    }

    /**
     * Sets a fixed amount taken off the subtotal, clearing any percentage; it has already been checked
     * as money. An amount over the subtotal is refused.
     */
    public void setDiscountAmount(BigDecimal discountAmount) {
        if (discountAmount.compareTo(subtotal()) > 0) {
            throw new InvoiceRejectedException("The discount cannot be more than the subtotal of "
                    + subtotal().toPlainString() + ".");
        }
        this.discountAmount = discountAmount;
        this.discountPct = BigDecimal.ZERO;
    }

    public List<InvoiceLine> getLines() {
        return lines;
    }

    /** Whether lines can still be added: only a draft is still being written. */
    public boolean isDraft() {
        return status == InvoiceStatus.DRAFT;
    }

    /** Whether the invoice has been voided: it no longer counts, takes no payments and cannot be changed. */
    public boolean isVoid() {
        return status == InvoiceStatus.VOID;
    }

    /** Voids a sent invoice. */
    public void markVoid() {
        this.status = InvoiceStatus.VOID;
    }

    /** Marks the invoice sent, issued on the given date and due on the given date. */
    public void markSent(LocalDate issueDate, LocalDate dueDate) {
        this.status = InvoiceStatus.SENT;
        this.issueDate = issueDate;
        this.dueDate = dueDate;
    }

    /** Puts a sent invoice back to draft, so it has to be sent again. */
    public void reopen() {
        this.status = InvoiceStatus.DRAFT;
    }

    /**
     * The status on the given day once the given amount has been paid against the grand total at the
     * tax rate: a sent invoice with something still due after its due date is overdue, part paid or not.
     */
    public InvoiceStatus statusWhenPaid(BigDecimal paid, BigDecimal taxRate, LocalDate today) {
        return statusWhenPaid(paid, null, taxRate, today);
    }

    /**
     * As {@link #statusWhenPaid(BigDecimal, BigDecimal, LocalDate)}, but a fully paid invoice whose
     * paid date (null when not known) is after its due date is paid late.
     */
    public InvoiceStatus statusWhenPaid(BigDecimal paid, LocalDate paidOn, BigDecimal taxRate, LocalDate today) {
        if (status != InvoiceStatus.SENT) {
            return status;
        }
        boolean due = amountDue(paid, taxRate).signum() > 0;
        if (due && dueDate != null && today.isAfter(dueDate)) {
            return InvoiceStatus.OVERDUE;
        }
        if (paid.signum() <= 0) {
            return status;
        }
        if (due) {
            return InvoiceStatus.PART_PAID;
        }
        return paidOn != null && dueDate != null && paidOn.isAfter(dueDate) ? InvoiceStatus.PAID_LATE
                : InvoiceStatus.PAID;
    }

    /**
     * The date the invoice was fully paid: the date of the payment that cleared its grand total at the
     * tax rate, taking the payments (not reversed) in date order; null while something is still due.
     */
    public LocalDate paidOn(List<Payment> payments, BigDecimal taxRate) {
        BigDecimal total = total(taxRate);
        if (status != InvoiceStatus.SENT || total.signum() <= 0) {
            return null;
        }
        List<Payment> inOrder = payments.stream().filter(p -> !p.isReversed())
                .sorted(Comparator.comparing(Payment::getDate).thenComparing(Payment::getId)).toList();
        BigDecimal sum = BigDecimal.ZERO;
        for (Payment p : inOrder) {
            sum = sum.add(p.amountTo(id));
            if (sum.signum() > 0 && total.subtract(sum).compareTo(WRITE_OFF_LIMIT) < 0) {
                return p.getDate();
            }
        }
        return null;
    }

    /**
     * What is still due once the given amount has been paid: the grand total less it, less anything
     * written off — so nothing once payments leave less than {@link #WRITE_OFF_LIMIT}.
     */
    public BigDecimal amountDue(BigDecimal paid, BigDecimal taxRate) {
        return total(taxRate).subtract(paid).subtract(writtenOff(paid, taxRate));
    }

    /**
     * What is written off once the given amount has been paid: when payments leave less than
     * {@link #WRITE_OFF_LIMIT} still due, all of what is left; otherwise nothing.
     */
    public BigDecimal writtenOff(BigDecimal paid, BigDecimal taxRate) {
        BigDecimal left = total(taxRate).subtract(paid);
        return paid.signum() > 0 && left.signum() > 0 && left.compareTo(WRITE_OFF_LIMIT) < 0
                ? left.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
    }

    /** Adds a line to the end of the invoice. */
    public void addLine(InvoiceLine line) {
        lines.add(line);
    }

    /** Whether the invoice has the line with the given id. */
    public boolean hasLine(Long lineId) {
        return lines.stream().anyMatch(l -> lineId.equals(l.getId()));
    }

    /**
     * Removes the line with the given id; whether the invoice had it. Refused when what is left would
     * be less than a fixed discount on the invoice.
     */
    public boolean removeLine(Long lineId) {
        InvoiceLine line = lines.stream().filter(l -> lineId.equals(l.getId())).findFirst().orElse(null);
        if (line == null) {
            return false;
        }
        BigDecimal left = subtotal().subtract(line.amount());
        if (discountAmount.signum() > 0 && left.compareTo(discountAmount) < 0) {
            throw new InvoiceRejectedException("This line cannot be removed: the $"
                    + left.setScale(2).toPlainString() + " left would be less than the $"
                    + discountAmount.setScale(2).toPlainString() + " discount.");
        }
        lines.remove(line);
        return true;
    }

    /**
     * Changes the line with the given id to the given values; whether the invoice had it. Refused when
     * the subtotal would then be less than a fixed discount on the invoice.
     */
    public boolean updateLine(Long lineId, String description, BigDecimal qty, BigDecimal unitPrice,
            boolean taxExempt, BigDecimal discountPct, BigDecimal taxRate) {
        InvoiceLine line = lines.stream().filter(l -> lineId.equals(l.getId())).findFirst().orElse(null);
        if (line == null) {
            return false;
        }
        BigDecimal changed = new InvoiceLine(description, qty, unitPrice, taxExempt, discountPct, taxRate).amount();
        BigDecimal left = subtotal().subtract(line.amount()).add(changed);
        if (discountAmount.signum() > 0 && left.compareTo(discountAmount) < 0) {
            throw new InvoiceRejectedException("This line cannot be changed: the $"
                    + left.setScale(2).toPlainString() + " subtotal would be less than the $"
                    + discountAmount.setScale(2).toPlainString() + " discount.");
        }
        line.update(description, qty, unitPrice, taxExempt, discountPct, taxRate);
        return true;
    }

    /**
     * The sum of the line amounts, each after its own discount and already to the cent — so the subtotal
     * matches what a client gets by adding up the lines shown.
     */
    public BigDecimal subtotal() {
        return lines.stream().map(InvoiceLine::amount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /**
     * The amount taken off: the fixed amount when there is one (never more than the subtotal, should
     * lines have been changed since), otherwise the discount percentage of the subtotal, rounded half
     * up to the cent. When there is a cap, it limits everything taken off — the line discounts plus
     * this — so this shrinks to what the line discounts leave under the cap, never below zero.
     */
    public BigDecimal discount() {
        BigDecimal discount = discountAmount.signum() > 0
                ? discountAmount.min(subtotal()).setScale(2, RoundingMode.HALF_UP)
                : subtotal().multiply(discountPct).divide(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        if (discountCap == null) {
            return discount;
        }
        BigDecimal lineDiscounts = lines.stream().map(InvoiceLine::discount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal left = discountCap.setScale(2, RoundingMode.HALF_UP).subtract(lineDiscounts).max(BigDecimal.ZERO.setScale(2));
        return discount.min(left);
    }

    /**
     * Each line's share of the discount, in the order of the lines. The discount's cents are split in
     * proportion to the line amounts: each line first gets the whole cents of its exact share
     * (rounded down), then the cents left over go one each to the lines with the largest leftover
     * fractions; between equal fractions the cent goes to the line with the larger amount, and between
     * equal amounts too to the lower line id. The shares add up to the discount.
     */
    public List<BigDecimal> discountShares() {
        BigInteger discountCents = discount().movePointRight(2).toBigIntegerExact();
        BigInteger subtotalCents = subtotal().movePointRight(2).toBigIntegerExact();
        int n = lines.size();
        BigInteger[] shares = new BigInteger[n];
        BigInteger[] leftovers = new BigInteger[n];
        BigInteger given = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            if (subtotalCents.signum() == 0) {
                shares[i] = BigInteger.ZERO;
                leftovers[i] = BigInteger.ZERO;
            } else {
                // The exact share is discount * amount / subtotal; all fractions share the subtotal
                // as denominator, so the remainders compare as the fractions do.
                BigInteger[] qr = discountCents.multiply(lines.get(i).amount().movePointRight(2).toBigIntegerExact())
                        .divideAndRemainder(subtotalCents);
                shares[i] = qr[0];
                leftovers[i] = qr[1];
            }
            given = given.add(shares[i]);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, BigInteger>comparing(i -> leftovers[i]).reversed()
                .thenComparing(Comparator.<Integer, BigDecimal>comparing(i -> lines.get(i).amount()).reversed())
                .thenComparing(i -> lines.get(i).getId(), Comparator.nullsLast(Comparator.naturalOrder())));
        int left = discountCents.subtract(given).intValueExact();
        for (int k = 0; k < left; k++) {
            int i = order.get(k);
            shares[i] = shares[i].add(BigInteger.ONE);
        }
        List<BigDecimal> result = new ArrayList<>(n);
        for (BigInteger share : shares) {
            result.add(new BigDecimal(share, 2));
        }
        return result;
    }

    /**
     * Each line's tax, in the order of the lines: the line's own rate (a percentage), or the given
     * workspace rate when it has none, on the line's amount less its share of the discount, rounded
     * half up to the cent. Tax-exempt lines have no tax.
     */
    public List<BigDecimal> lineTaxes(BigDecimal taxRate) {
        List<BigDecimal> shares = discountShares();
        List<BigDecimal> taxes = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            InvoiceLine line = lines.get(i);
            taxes.add(line.isTaxExempt() ? BigDecimal.ZERO.setScale(2)
                    : line.amount().subtract(shares.get(i)).multiply(line.taxRate(taxRate)).divide(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP));
        }
        return taxes;
    }

    /** The tax charged at the given rate (a percentage): the sum of the line taxes. */
    public BigDecimal tax(BigDecimal taxRate) {
        return lineTaxes(taxRate).stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** The grand total: the subtotal less the discount, plus tax at the given rate. */
    public BigDecimal total(BigDecimal taxRate) {
        return subtotal().subtract(discount()).add(tax(taxRate));
    }
}
