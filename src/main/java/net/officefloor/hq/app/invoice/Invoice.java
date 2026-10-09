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
import java.util.function.UnaryOperator;
import net.officefloor.hq.app.project.Project;

/**
 * An invoice raised against a project. It is built from line items, and can take a percentage and/or
 * a flat amount off their sum as a discount (the flat amount shared across the lines in proportion to
 * what each charges, and never more than is left after the percentage), then add a percentage sales tax on what is left of the taxable lines only
 * (tax-free lines are never taxed), and optionally a second tax (a levy) worked out on the same taxable base;
 * its stored amount is always that subtotal less the discount plus the tax plus the levy.
 * <p>
 * Every figure is worked out line by line: each line is rounded to the cent first and the rounded lines
 * are then added up, and the tax and levy are likewise worked out and rounded on each taxable line before
 * being added up, so the totals are sums of rounded lines rather than a rounded sum.
 * <p>
 * A tax-inclusive invoice (for a client whose prices already include tax) instead works the tax and
 * levy back out of the taxable lines after the discount: they are inside the price, so its amount is
 * just the subtotal less the discount, and the taxable base is what is left once they are taken out.
 * <p>
 * A tax-exempt invoice (for a tax-exempt client) has no taxable lines at all: it carries no tax or levy
 * whatever its lines or rates say, so its amount is the subtotal less the discount.
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

    /** A flat amount taken off the subtotal (after the percentage); zero when there is none. */
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO.setScale(2);

    @Column(name = "tax_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxPct = BigDecimal.ZERO.setScale(2);

    @Column(name = "levy_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal levyPct = BigDecimal.ZERO.setScale(2);

    /** Whether the prices already include the tax and levy, so they are backed out rather than added on. */
    @Column(name = "tax_inclusive", nullable = false)
    private boolean taxInclusive;

    /** Whether the invoice is for a tax-exempt client, so it carries no tax or levy whatever its lines say. */
    @Column(name = "tax_exempt", nullable = false)
    private boolean taxExempt;

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
        this.taxExempt = project.getClient().isTaxExempt();
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

    /**
     * The overall tax rate that actually ended up on the invoice: the sales tax and levy together as a
     * percentage of the total before tax, to two decimal places. It is below the headline rates when some
     * lines are tax-free, and zero when there is nothing before tax to charge it on.
     */
    public BigDecimal getEffectiveTaxPct() {
        BigDecimal totalExTax = getTotalExTax();
        if (totalExTax.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return getTax().add(getLevy()).multiply(BigDecimal.valueOf(100)).divide(totalExTax, 2, RoundingMode.HALF_UP);
    }

    /** The percentage taken off the subtotal; zero when there is no discount. */
    public BigDecimal getDiscountPct() {
        return discountPct;
    }

    /** The flat amount asked to be taken off the subtotal; zero when there is no flat discount. */
    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    /** What the line items add up to, before any discount: the sum of each line rounded to the cent. */
    public BigDecimal getSubtotal() {
        return lineItems.stream().map(InvoiceLineItem::getAmount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** How much the discount (the percentage and the flat amount together) takes off the subtotal, to the cent. */
    public BigDecimal getDiscount() {
        BigDecimal subtotal = getSubtotal();
        return percentOf(subtotal, discountPct).add(flatDiscountOn(subtotal));
    }

    /** The sales tax percentage added after the discount; zero when there is no tax. */
    public BigDecimal getTaxPct() {
        return taxPct;
    }

    /** Whether the prices already include the tax and levy, so they are worked back out rather than added on. */
    public boolean isTaxInclusive() {
        return taxInclusive;
    }

    /** Whether the invoice is for a tax-exempt client, so none of its lines are taxed. */
    public boolean isTaxExempt() {
        return taxExempt;
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

    /**
     * How much sales tax is added on the taxable base (or is inside it, when tax-inclusive): the tax on
     * each taxable line rounded to the cent, added up.
     */
    public BigDecimal getTax() {
        return sumOverTaxableLines(this::taxOn);
    }

    /** The levy (second tax) percentage added on top of the sales tax; zero when there is no levy. */
    public BigDecimal getLevyPct() {
        return levyPct;
    }

    /**
     * How much levy is added on the taxable base (or is inside it, when tax-inclusive): the levy on each
     * taxable line rounded to the cent, added up.
     */
    public BigDecimal getLevy() {
        return sumOverTaxableLines(this::levyOn);
    }

    /** The taxable lines (leaving out tax-free ones), each less the discount taken off it, added up. */
    private BigDecimal getDiscountedTaxable() {
        return sumOverTaxableLines(line -> line);
    }

    /**
     * Works a figure out on each taxable line after its discount, to the cent, and adds them up. A
     * tax-exempt invoice has no taxable lines.
     */
    private BigDecimal sumOverTaxableLines(UnaryOperator<BigDecimal> perLine) {
        return lineItems.stream().filter(l -> !taxExempt && !l.isTaxExempt()).map(InvoiceLineItem::getAmount)
                .map(amount -> perLine.apply(amount.subtract(discountOn(amount))))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
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
        applyDiscount(discountPct, BigDecimal.ZERO);
    }

    /** Sets the percentage and the flat amount taken off this invoice and reworks its amount to match. */
    public void applyDiscount(BigDecimal discountPct, BigDecimal discountAmount) {
        this.discountPct = discountPct.setScale(2, RoundingMode.HALF_UP);
        this.discountAmount = discountAmount.setScale(2, RoundingMode.HALF_UP);
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

    /** Sets whether this invoice is for a tax-exempt client and reworks its amount to match. */
    public void applyTaxExempt(boolean taxExempt) {
        this.taxExempt = taxExempt;
        recalculateAmount();
    }

    /** Sets the levy (second tax) percentage added to this invoice and reworks its amount to match. */
    public void applyLevy(BigDecimal levyPct) {
        this.levyPct = levyPct.setScale(2, RoundingMode.HALF_UP);
        recalculateAmount();
    }

    /**
     * The discount taken off one line, to the cent: its percentage, plus its share of the flat amount in
     * proportion to what the line charges out of the subtotal.
     */
    private BigDecimal discountOn(BigDecimal line) {
        BigDecimal pct = percentOf(line, discountPct);
        BigDecimal subtotal = getSubtotal();
        if (subtotal.signum() == 0) {
            return pct;
        }
        BigDecimal flatShare = flatDiscountOn(subtotal).multiply(line).divide(subtotal, 2, RoundingMode.HALF_UP);
        return pct.add(flatShare);
    }

    /** The flat amount actually taken off the subtotal: never more than is left after the percentage. */
    private BigDecimal flatDiscountOn(BigDecimal subtotal) {
        BigDecimal left = subtotal.subtract(percentOf(subtotal, discountPct)).max(BigDecimal.ZERO.setScale(2));
        return discountAmount.min(left);
    }

    /** The sales tax on one discounted taxable line (or inside it, when tax-inclusive), to the cent. */
    private BigDecimal taxOn(BigDecimal line) {
        return taxInclusive ? includedIn(line, taxPct) : percentOf(line, taxPct);
    }

    /** The levy on one discounted taxable line (or inside it, when tax-inclusive), to the cent. */
    private BigDecimal levyOn(BigDecimal line) {
        return taxInclusive ? includedIn(line, levyPct) : percentOf(line, levyPct);
    }

    private static BigDecimal percentOf(BigDecimal base, BigDecimal pct) {
        return base.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private void recalculateAmount() {
        BigDecimal subtotal = getSubtotal();
        BigDecimal discounted = subtotal.subtract(getDiscount());
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
