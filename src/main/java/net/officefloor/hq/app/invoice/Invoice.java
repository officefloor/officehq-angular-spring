package net.officefloor.hq.app.invoice;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.officefloor.hq.app.project.Project;

/**
 * An invoice raised against a project. It is built from line items, and can take a percentage off
 * their sum as a discount, then add a percentage sales tax on what is left of the taxable lines only
 * (tax-free lines are never taxed), and optionally a second tax (a levy) worked out on the same taxable base;
 * its stored amount is always that subtotal less the discount plus the tax plus the levy.
 * <p>
 * A tax-inclusive invoice (for a client whose prices already include tax) instead works the tax and
 * levy back out of the taxable lines after the discount: they are inside the price, so its amount is
 * just the subtotal less the discount, and the taxable base is what is left once they are taken out.
 */
@Entity
@Table(name = "invoice")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO.setScale(2);

    @Column(name = "discount_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPct = BigDecimal.ZERO.setScale(2);

    @Column(name = "tax_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxPct = BigDecimal.ZERO.setScale(2);

    @Column(name = "levy_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal levyPct = BigDecimal.ZERO.setScale(2);

    /** Whether the prices already include the tax and levy, so they are backed out rather than added on. */
    @Column(name = "tax_inclusive", nullable = false)
    private boolean taxInclusive;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(name = "issued_date", nullable = false)
    private LocalDate issuedDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<InvoiceLineItem> lineItems = new ArrayList<>();

    protected Invoice() {
    }

    public Invoice(Project project, LocalDate issuedDate, LocalDate dueDate) {
        this.project = project;
        this.issuedDate = issuedDate;
        this.dueDate = dueDate;
        this.taxInclusive = project.getClient().isTaxInclusive();
    }

    public Long getId() {
        return id;
    }

    public Project getProject() {
        return project;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * The total before tax: the amount owed less the sales tax and levy, whether they were added on or
     * are included in the prices.
     */
    public BigDecimal getTotalExTax() {
        return amount.subtract(getTax()).subtract(getLevy());
    }

    /** The percentage taken off the subtotal; zero when there is no discount. */
    public BigDecimal getDiscountPct() {
        return discountPct;
    }

    /** What the line items add up to, before any discount. */
    public BigDecimal getSubtotal() {
        return lineItems.stream().map(InvoiceLineItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** How much the discount takes off the subtotal, to the cent. */
    public BigDecimal getDiscount() {
        return discountOn(getSubtotal());
    }

    /** The sales tax percentage added after the discount; zero when there is no tax. */
    public BigDecimal getTaxPct() {
        return taxPct;
    }

    /** Whether the prices already include the tax and levy, so they are worked back out rather than added on. */
    public boolean isTaxInclusive() {
        return taxInclusive;
    }

    /**
     * What the sales tax is charged on: the taxable lines (leaving out tax-free ones) less the discount
     * taken off them, to the cent. On a tax-inclusive invoice that is what is left of them once the tax
     * and levy inside them are taken out.
     */
    public BigDecimal getTaxableBase() {
        BigDecimal gross = getDiscountedTaxable();
        return taxInclusive ? gross.subtract(getTax()).subtract(getLevy()) : gross;
    }

    /** How much sales tax is added on the taxable base (or is inside it, when tax-inclusive), to the cent. */
    public BigDecimal getTax() {
        return taxInclusive ? includedIn(getDiscountedTaxable(), taxPct) : taxOn(getTaxableBase());
    }

    /** The levy (second tax) percentage added on top of the sales tax; zero when there is no levy. */
    public BigDecimal getLevyPct() {
        return levyPct;
    }

    /** How much levy is added on the taxable base (or is inside it, when tax-inclusive), to the cent. */
    public BigDecimal getLevy() {
        return taxInclusive ? includedIn(getDiscountedTaxable(), levyPct) : percentOf(getTaxableBase(), levyPct);
    }

    /** The taxable lines (leaving out tax-free ones) less the discount taken off them, to the cent. */
    private BigDecimal getDiscountedTaxable() {
        BigDecimal taxable = lineItems.stream().filter(l -> !l.isTaxExempt()).map(InvoiceLineItem::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        return taxable.subtract(discountOn(taxable));
    }

    /**
     * The part of a tax-inclusive price that is the given percentage, worked back out of it: the price
     * divided in proportion to the sales tax and levy rates it includes (e.g. 20% tax in 120 is 20).
     */
    private BigDecimal includedIn(BigDecimal price, BigDecimal pct) {
        BigDecimal grossPct = BigDecimal.valueOf(100).add(taxPct).add(levyPct);
        return price.multiply(pct).divide(grossPct, 2, RoundingMode.HALF_UP);
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public LocalDate getIssuedDate() {
        return issuedDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public List<InvoiceLineItem> getLineItems() {
        return List.copyOf(lineItems);
    }

    /** Adds a line to this invoice and reworks its amount to include what the line charges. */
    public InvoiceLineItem addLineItem(String description, BigDecimal qty, String unit, BigDecimal unitPrice) {
        return addLineItem(description, qty, unit, unitPrice, false);
    }

    /** Adds a line, taxable or tax-free, to this invoice and reworks its amount to include what the line charges. */
    public InvoiceLineItem addLineItem(String description, BigDecimal qty, String unit, BigDecimal unitPrice,
            boolean taxExempt) {
        InvoiceLineItem item = new InvoiceLineItem(this, description, qty, unit, unitPrice, taxExempt);
        lineItems.add(item);
        recalculateAmount();
        return item;
    }

    /** Changes one of this invoice's lines and reworks its amount to match. */
    public void updateLineItem(InvoiceLineItem item, String description, BigDecimal qty, String unit,
            BigDecimal unitPrice, boolean taxExempt) {
        item.update(description, qty, unit, unitPrice, taxExempt);
        recalculateAmount();
    }

    /** Removes a line from this invoice and reworks its amount to no longer include what the line charged. */
    public void removeLineItem(InvoiceLineItem item) {
        lineItems.remove(item);
        recalculateAmount();
    }

    /** The line on this invoice with the given id, if there is one. */
    public Optional<InvoiceLineItem> findLineItem(Long lineItemId) {
        return lineItems.stream().filter(l -> l.getId().equals(lineItemId)).findFirst();
    }

    /** Sets the percentage taken off this invoice and reworks its amount to match. */
    public void applyDiscount(BigDecimal discountPct) {
        this.discountPct = discountPct.setScale(2, RoundingMode.HALF_UP);
        recalculateAmount();
    }

    /** Sets the sales tax percentage added to this invoice and reworks its amount to match. */
    public void applyTax(BigDecimal taxPct) {
        this.taxPct = taxPct.setScale(2, RoundingMode.HALF_UP);
        recalculateAmount();
    }

    /** Sets whether this invoice's prices already include tax and reworks its amount to match. */
    public void applyTaxInclusive(boolean taxInclusive) {
        this.taxInclusive = taxInclusive;
        recalculateAmount();
    }

    /** Sets the levy (second tax) percentage added to this invoice and reworks its amount to match. */
    public void applyLevy(BigDecimal levyPct) {
        this.levyPct = levyPct.setScale(2, RoundingMode.HALF_UP);
        recalculateAmount();
    }

    private BigDecimal discountOn(BigDecimal subtotal) {
        return subtotal.multiply(discountPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal taxOn(BigDecimal taxableBase) {
        return percentOf(taxableBase, taxPct);
    }

    private static BigDecimal percentOf(BigDecimal base, BigDecimal pct) {
        return base.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private void recalculateAmount() {
        BigDecimal subtotal = getSubtotal();
        BigDecimal discounted = subtotal.subtract(discountOn(subtotal));
        this.amount = taxInclusive ? discounted : discounted.add(getTax()).add(getLevy());
    }

    /** Marks this invoice as sent to the client. */
    public void markSent() {
        this.status = InvoiceStatus.SENT;
    }

    /** Cancels this invoice, so it is no longer owed. */
    public void markVoid() {
        this.status = InvoiceStatus.VOID;
    }

    /** What is left to pay on this invoice given the total paid against it; nothing once it is void. */
    public BigDecimal amountDue(BigDecimal paid) {
        return status == InvoiceStatus.VOID ? BigDecimal.ZERO.setScale(2) : amount.subtract(paid);
    }

    /**
     * Works out this sent invoice's status from the total paid against it: PAID once the payments
     * cover the amount, PARTIAL once something has been paid, otherwise still SENT.
     */
    public void applyPaidTotal(BigDecimal paid) {
        if (status == InvoiceStatus.DRAFT || status == InvoiceStatus.VOID) {
            throw new IllegalStateException("A " + status.name().toLowerCase() + " invoice cannot be paid");
        }
        if (paid.compareTo(amount) >= 0) {
            this.status = InvoiceStatus.PAID;
        } else if (paid.signum() > 0) {
            this.status = InvoiceStatus.PARTIAL;
        } else {
            this.status = InvoiceStatus.SENT;
        }
    }
}
