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

    @OneToMany(cascade = CascadeType.ALL)
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

    /** Sets the percentage taken off the subtotal; it has already been checked. */
    public void setDiscountPct(BigDecimal discountPct) {
        this.discountPct = discountPct;
    }

    public List<InvoiceLine> getLines() {
        return lines;
    }

    /** Whether lines can still be added: only a draft is still being written. */
    public boolean isDraft() {
        return status == InvoiceStatus.DRAFT;
    }

    /** Marks the invoice sent; its issue and due dates stay as they are. */
    public void markSent() {
        this.status = InvoiceStatus.SENT;
    }

    /** Puts a sent invoice back to draft, so it has to be sent again. */
    public void reopen() {
        this.status = InvoiceStatus.DRAFT;
    }

    /** The status once the given amount has been paid against the grand total at the tax rate. */
    public InvoiceStatus statusWhenPaid(BigDecimal paid, BigDecimal taxRate) {
        if (status != InvoiceStatus.SENT || paid.signum() <= 0) {
            return status;
        }
        return amountDue(paid, taxRate).signum() > 0 ? InvoiceStatus.PART_PAID : InvoiceStatus.PAID;
    }

    /** What is still due once the given amount has been paid: the grand total less it. */
    public BigDecimal amountDue(BigDecimal paid, BigDecimal taxRate) {
        return total(taxRate).subtract(paid);
    }

    /** Adds a line to the end of the invoice. */
    public void addLine(InvoiceLine line) {
        lines.add(line);
    }

    /**
     * The sum of the line amounts, each after its own discount and already to the cent — so the subtotal
     * matches what a client gets by adding up the lines shown.
     */
    public BigDecimal subtotal() {
        return lines.stream().map(InvoiceLine::amount)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** The amount taken off: the discount percentage of the subtotal, rounded half up to the cent. */
    public BigDecimal discount() {
        return subtotal().multiply(discountPct).divide(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Each line's share of the discount, in the order of the lines. The discount's cents are split in
     * proportion to the line amounts: each line first gets the whole cents of its exact share
     * (rounded down), then the cents left over go one each to the lines with the largest leftover
     * fractions, equal fractions going to the lower line id. The shares add up to the discount.
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
     * The tax charged at the given rate (a percentage): each taxable line's amount less its share of
     * the discount, added up, and the rate charged on that, rounded half up to the cent. Tax-exempt
     * lines are left out.
     */
    public BigDecimal tax(BigDecimal taxRate) {
        List<BigDecimal> shares = discountShares();
        BigDecimal taxable = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isTaxExempt()) {
                taxable = taxable.add(lines.get(i).amount().subtract(shares.get(i)));
            }
        }
        return taxable.multiply(taxRate).divide(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** The grand total: the subtotal less the discount, plus tax at the given rate. */
    public BigDecimal total(BigDecimal taxRate) {
        return subtotal().subtract(discount()).add(tax(taxRate));
    }
}
