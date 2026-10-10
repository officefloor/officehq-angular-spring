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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import net.officefloor.hq.app.project.Project;

/**
 * An invoice raised against a project. It is built from line items, and can take several discounts off
 * their sum before tax, each either a percentage or a flat amount (the percentages each taking their share
 * of the subtotal first (a percentage capped at a maximum never taking off more than that), then the flat amounts, shared across the lines in proportion to what each
 * charges; together never more than the subtotal), then add a percentage sales tax on what is left of the taxable lines only
 * (tax-free lines are never taxed), and optionally a second tax (a levy) worked out on the same taxable base;
 * its stored amount is always that subtotal less the discount plus the tax plus the levy, plus any flat
 * surcharge (such as a handling fee), which is added on last and is never taxed.
 * <p>
 * Every figure is worked out line by line: each line is rounded to the cent first and the rounded lines
 * are then added up, and the tax and levy are likewise worked out and rounded on each taxable line before
 * being added up, so the totals are sums of rounded lines rather than a rounded sum.
 * <p>
 * A tax-inclusive invoice (for a client whose prices already include tax) instead works the tax and
 * levy back out of the taxable lines after the discount: they are inside the price, so its amount is
 * just the subtotal less the discount (plus any surcharge), and the taxable base is what is left once they are taken out.
 * <p>
 * A tax-exempt invoice (for a tax-exempt client) has no taxable lines at all: it carries no tax or levy
 * whatever its lines or rates say, so its amount is the subtotal less the discount (plus any surcharge).
 * <p>
 * An invoice can carry a minimum charge: when its net total (everything above, worked out as usual) comes
 * out under it, the minimum is billed instead, and the invoice records that the minimum was applied.
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

    /** The currency the invoice is billed in when it differs from its client's; null means the client's currency. */
    @Column(length = 3)
    private String currency;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO.setScale(2);

    @Column(name = "tax_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal taxPct = BigDecimal.ZERO.setScale(2);

    @Column(name = "levy_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal levyPct = BigDecimal.ZERO.setScale(2);

    /** A flat amount (such as a handling fee) added to the total after tax; zero when there is none. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal surcharge = BigDecimal.ZERO.setScale(2);

    /** The least the invoice bills; zero when there is no minimum charge. */
    @Column(name = "minimum_charge", nullable = false, precision = 12, scale = 2)
    private BigDecimal minimumCharge = BigDecimal.ZERO.setScale(2);

    /** Whether the prices already include the tax and levy, so they are backed out rather than added on. */
    @Column(name = "tax_inclusive", nullable = false)
    private boolean taxInclusive;

    /** Whether the invoice is for a tax-exempt client, so it carries no tax or levy whatever its lines say. */
    @Column(name = "tax_exempt", nullable = false)
    private boolean taxExempt;

    /** The percentage taken off what is owed when paid early; zero when no early-payment discount is offered. */
    @Column(name = "early_payment_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal earlyPaymentPct = BigDecimal.ZERO.setScale(2);

    /** How many days after being issued the invoice must be paid within to get the early-payment discount. */
    @Column(name = "early_payment_days", nullable = false)
    private int earlyPaymentDays;

    /** The percentage of the amount given back when paid before the due date; zero when no rebate is offered. */
    @Column(name = "rebate_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal rebatePct = BigDecimal.ZERO.setScale(2);

    /** The late fee charged for each day the invoice is overdue once sent; zero when none is charged. */
    @Column(name = "late_fee_per_day", nullable = false, precision = 12, scale = 2)
    private BigDecimal lateFeePerDay = BigDecimal.ZERO.setScale(2);

    @Column(name = "instalment_interest_per_day", nullable = false, precision = 12, scale = 2)
    private BigDecimal instalmentInterestPerDay = BigDecimal.ZERO.setScale(2);

    /** The percentage of the amount held back as retention, not due yet; zero when none is held back. */
    @Column(name = "retention_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal retentionPct = BigDecimal.ZERO.setScale(2);

    /** Whether the retention has been released, once the job is finished, so it is now due. */
    @Column(name = "retention_released", nullable = false)
    private boolean retentionReleased;

    /** The part of the invoice written off as bad debt, no longer owed; zero when none has been written off. */
    @Column(name = "write_off_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal writeOffAmount = BigDecimal.ZERO.setScale(2);

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

    /** The discounts taken off the subtotal before tax, in the order they were added. */
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<InvoiceDiscount> discounts = new ArrayList<>();

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

    /** The currency the invoice's money is in: its own, or else its client's. */
    public String getCurrency() {
        return currency != null ? currency : project.getClient().getCurrency();
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

    /** The discounts on this invoice, in the order they were added. */
    public List<InvoiceDiscount> getDiscounts() {
        return List.copyOf(discounts);
    }

    /**
     * The percentages taken off the subtotal, added up (never more than 100); zero when there is no
     * percentage discount.
     */
    public BigDecimal getDiscountPct() {
        return discounts.stream().map(InvoiceDiscount::getDiscountPct).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add)
                .min(BigDecimal.valueOf(100).setScale(2));
    }

    /** The flat amounts asked to be taken off the subtotal, added up; zero when there is no flat discount. */
    public BigDecimal getDiscountAmount() {
        return discounts.stream().map(InvoiceDiscount::getDiscountAmount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /**
     * How much one of this invoice's discounts actually takes off the subtotal, to the cent. The
     * percentages are taken first, each its share of the subtotal (never more than its cap, if it has
     * one), then the flat amounts in the order they were added; none takes off more than is left after
     * those before it.
     */
    public BigDecimal discountTakenBy(InvoiceDiscount discount) {
        BigDecimal subtotal = getSubtotal();
        BigDecimal left = subtotal;
        for (InvoiceDiscount d : discountsInOrderTaken()) {
            BigDecimal wanted = d.isPercentage() ? percentWanted(subtotal, d) : d.getDiscountAmount();
            BigDecimal taken = wanted.min(left.max(BigDecimal.ZERO.setScale(2)));
            if (d == discount) {
                return taken;
            }
            left = left.subtract(taken);
        }
        return BigDecimal.ZERO.setScale(2);
    }

    /** What a percentage discount asks to take off the subtotal: its share, never more than its cap. */
    private static BigDecimal percentWanted(BigDecimal subtotal, InvoiceDiscount discount) {
        BigDecimal share = percentOf(subtotal, discount.getDiscountPct());
        return isCappedAt(subtotal, discount) ? share.min(discount.getDiscountCap()) : share;
    }

    /** Whether a discount's cap limits what it takes off the given subtotal. */
    private static boolean isCappedAt(BigDecimal subtotal, InvoiceDiscount discount) {
        return discount.getDiscountCap() != null
                && percentOf(subtotal, discount.getDiscountPct()).compareTo(discount.getDiscountCap()) > 0;
    }

    /** The percentage discounts first, then the flat ones, each in the order they were added. */
    private List<InvoiceDiscount> discountsInOrderTaken() {
        List<InvoiceDiscount> ordered = new ArrayList<>(discounts.stream().filter(InvoiceDiscount::isPercentage).toList());
        ordered.addAll(discounts.stream().filter(d -> !d.isPercentage()).toList());
        return ordered;
    }

    /**
     * What the line items add up to, before the invoice's discount: the sum of each line after its own
     * discount, rounded to the cent.
     */
    public BigDecimal getSubtotal() {
        return lineItems.stream().map(InvoiceLineItem::getAmount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** How much the discounts take off the subtotal together (what each takes off, added up), to the cent. */
    public BigDecimal getDiscount() {
        return discounts.stream().map(this::discountTakenBy).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /**
     * How much the client saves on this invoice: every discount added together, both what the lines take
     * off themselves and what the invoice's discounts take off the subtotal, to the cent.
     */
    public BigDecimal getTotalSavings() {
        BigDecimal lineSavings = lineItems.stream().map(l -> l.getGrossAmount().subtract(l.getAmount()))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        return lineSavings.add(getDiscount());
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

    /** The flat amount (such as a handling fee) added to the total after tax; zero when there is none. */
    public BigDecimal getSurcharge() {
        return surcharge;
    }

    /** The least this invoice bills; zero when there is no minimum charge. */
    public BigDecimal getMinimumCharge() {
        return minimumCharge;
    }

    /**
     * What the invoice comes to before any minimum charge: the subtotal less the discount plus the tax and
     * levy (unless they are inside the prices), plus the surcharge.
     */
    public BigDecimal getNetTotal() {
        BigDecimal discounted = getSubtotal().subtract(getDiscount());
        return (taxInclusive ? discounted : discounted.add(getTax()).add(getLevy())).add(surcharge);
    }

    /** Whether the net total came out under the minimum charge, so the minimum is billed instead. */
    public boolean isMinimumApplied() {
        return getNetTotal().compareTo(minimumCharge) < 0;
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

    /** The percentage taken off what is owed when paid early; zero when none is offered. */
    public BigDecimal getEarlyPaymentPct() {
        return earlyPaymentPct;
    }

    /** How many days after being issued the invoice must be paid within to get the early-payment discount. */
    public int getEarlyPaymentDays() {
        return earlyPaymentDays;
    }

    /** Whether an early-payment discount is offered: a percentage off when paid within some days. */
    public boolean offersEarlyPayment() {
        return earlyPaymentPct.signum() > 0 && earlyPaymentDays > 0;
    }

    /** The last day the invoice can be paid to get the early-payment discount; null when none is offered. */
    public LocalDate getEarlyPaymentBy() {
        return offersEarlyPayment() ? issuedDate.plusDays(earlyPaymentDays) : null;
    }

    /**
     * The reduced amount to pay when settling early: the amount less the early-payment percentage of it,
     * to the cent; null when no early-payment discount is offered.
     */
    public BigDecimal getEarlyPaymentAmount() {
        return offersEarlyPayment() ? amount.subtract(percentOf(amount, earlyPaymentPct)) : null;
    }

    /** The percentage of the amount given back when paid before the due date; zero when none is offered. */
    public BigDecimal getRebatePct() {
        return rebatePct;
    }

    /** The settlement rebate for paying before the due date: the rebate percentage of the amount, to the cent; null when none is offered. */
    public BigDecimal getRebate() {
        return rebatePct.signum() > 0 ? percentOf(amount, rebatePct) : null;
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
        return addLineItem(description, qty, unit, unitPrice, false, BigDecimal.ZERO);
    }

    /**
     * Adds a line, taxable or tax-free and with any discount of its own, to this invoice and reworks its
     * amount to include what the line charges after that discount.
     */
    public InvoiceLineItem addLineItem(String description, BigDecimal qty, String unit, BigDecimal unitPrice,
            boolean taxExempt, BigDecimal discountPct) {
        InvoiceLineItem item = new InvoiceLineItem(this, description, qty, unit, unitPrice, taxExempt, discountPct);
        lineItems.add(item);
        recalculateAmount();
        return item;
    }

    /** Changes one of this invoice's lines and reworks its amount to match. */
    public void updateLineItem(InvoiceLineItem item, String description, BigDecimal qty, String unit,
            BigDecimal unitPrice, boolean taxExempt, BigDecimal discountPct) {
        item.update(description, qty, unit, unitPrice, taxExempt, discountPct);
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

    /** Replaces this invoice's discounts with a single percentage and reworks its amount to match. */
    public void applyDiscount(BigDecimal discountPct) {
        applyDiscount(discountPct, BigDecimal.ZERO);
    }

    /**
     * Replaces this invoice's discounts with a single one, either a percentage or a flat amount (the other
     * being zero; zero for both leaves no discount), and reworks its amount to match.
     */
    public void applyDiscount(BigDecimal discountPct, BigDecimal discountAmount) {
        applyDiscount(discountPct, discountAmount, null);
    }

    /**
     * Replaces this invoice's discounts with a single one, either a percentage (optionally capped at the
     * most it takes off) or a flat amount, and reworks its amount to match.
     */
    public void applyDiscount(BigDecimal discountPct, BigDecimal discountAmount, BigDecimal discountCap) {
        InvoiceDiscount discount = new InvoiceDiscount(this, discountPct, discountAmount, discountCap);
        discounts.clear();
        if (discountPct.signum() != 0 || discountAmount.signum() != 0) {
            discounts.add(discount);
        }
        recalculateAmount();
    }

    /**
     * Adds another discount to this invoice, either a percentage or a flat amount (the other being zero),
     * and reworks its amount to match.
     */
    public InvoiceDiscount addDiscount(BigDecimal discountPct, BigDecimal discountAmount) {
        return addDiscount(discountPct, discountAmount, null);
    }

    /**
     * Adds another discount to this invoice, either a percentage (optionally capped at the most it takes
     * off) or a flat amount, and reworks its amount to match.
     */
    public InvoiceDiscount addDiscount(BigDecimal discountPct, BigDecimal discountAmount, BigDecimal discountCap) {
        if (discountPct.signum() == 0 && discountAmount.signum() == 0) {
            throw new IllegalArgumentException("A discount takes off a percentage or a flat amount");
        }
        InvoiceDiscount discount = new InvoiceDiscount(this, discountPct, discountAmount, discountCap);
        discounts.add(discount);
        recalculateAmount();
        return discount;
    }

    /** Removes a discount from this invoice and reworks its amount to match. */
    public void removeDiscount(InvoiceDiscount discount) {
        discounts.remove(discount);
        recalculateAmount();
    }

    /** The discount on this invoice with the given id, if there is one. */
    public Optional<InvoiceDiscount> findDiscount(Long discountId) {
        return discounts.stream().filter(d -> d.getId().equals(discountId)).findFirst();
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

    /** Sets the flat surcharge (such as a handling fee) added to this invoice and reworks its amount to match. */
    public void applySurcharge(BigDecimal surcharge) {
        this.surcharge = surcharge.setScale(2, RoundingMode.HALF_UP);
        recalculateAmount();
    }

    /** Sets the minimum charge billed on this invoice and reworks its amount to match. */
    public void applyMinimumCharge(BigDecimal minimumCharge) {
        this.minimumCharge = minimumCharge.setScale(2, RoundingMode.HALF_UP);
        recalculateAmount();
    }

    /** Sets the late fee charged for each day this invoice is overdue. It does not change the amount invoiced. */
    public void applyLateFee(BigDecimal lateFeePerDay) {
        this.lateFeePerDay = lateFeePerDay.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getLateFeePerDay() {
        return lateFeePerDay;
    }

    /** Sets the interest charged for each day an instalment of this invoice is paid late. */
    public void applyInstalmentInterest(BigDecimal interestPerDay) {
        this.instalmentInterestPerDay = interestPerDay.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getInstalmentInterestPerDay() {
        return instalmentInterestPerDay;
    }

    /** Sets the percentage of this invoice held back as retention. It does not change the amount invoiced. */
    public void applyRetention(BigDecimal retentionPct) {
        this.retentionPct = retentionPct.setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getRetentionPct() {
        return retentionPct;
    }

    /** Releases the retention held back, so it becomes due. The percentage is kept on record. */
    public void releaseRetention() {
        this.retentionReleased = true;
    }

    public boolean isRetentionReleased() {
        return retentionReleased;
    }

    /**
     * The amount held back as retention: the retention percentage of the amount invoiced, or nothing once
     * the retention has been released.
     */
    public BigDecimal getRetention() {
        return retentionReleased ? BigDecimal.ZERO.setScale(2) : percentOf(amount, retentionPct);
    }

    /** What is due now: the amount invoiced less the retention held back. */
    public BigDecimal getDueNow() {
        return amount.subtract(getRetention());
    }

    /**
     * How many days past its due date the invoice is on the given day; zero unless it has been sent and
     * is still owed (sent or part paid).
     */
    public long daysLate(InvoiceStatus status, LocalDate today) {
        if (status != InvoiceStatus.SENT && status != InvoiceStatus.PARTIAL) {
            return 0;
        }
        return Math.max(0, ChronoUnit.DAYS.between(dueDate, today));
    }

    /** The late fee accrued by the given day: the fee per day times the days the invoice is overdue. */
    public BigDecimal lateFee(InvoiceStatus status, LocalDate today) {
        return lateFeePerDay.multiply(BigDecimal.valueOf(daysLate(status, today))).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Sets the early-payment discount offered on this invoice: the percentage taken off if it is paid
     * within the given number of days of being issued. It does not change the amount owed.
     */
    public void applyEarlyPayment(BigDecimal earlyPaymentPct, int earlyPaymentDays) {
        this.earlyPaymentPct = earlyPaymentPct.setScale(2, RoundingMode.HALF_UP);
        this.earlyPaymentDays = earlyPaymentDays;
    }

    /**
     * Sets the settlement rebate offered on this invoice: the percentage of the amount given back if it is
     * paid before the due date. It does not change the amount invoiced.
     */
    public void applyRebate(BigDecimal rebatePct) {
        this.rebatePct = rebatePct.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The discount taken off one line, to the cent: the uncapped percentages together, plus its share of
     * what the flat amounts and the capped percentages take off in proportion to what the line charges
     * out of the subtotal.
     */
    private BigDecimal discountOn(BigDecimal line) {
        BigDecimal subtotal = getSubtotal();
        BigDecimal pct = percentOf(line, uncappedPct(subtotal));
        if (subtotal.signum() == 0) {
            return pct;
        }
        BigDecimal flatShare = sharedDiscount(subtotal).multiply(line).divide(subtotal, 2, RoundingMode.HALF_UP);
        return pct.add(flatShare);
    }

    /** The percentages not limited by a cap, added up (never more than 100). */
    private BigDecimal uncappedPct(BigDecimal subtotal) {
        return discounts.stream().filter(d -> d.isPercentage() && !isCappedAt(subtotal, d)).map(InvoiceDiscount::getDiscountPct)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add).min(BigDecimal.valueOf(100).setScale(2));
    }

    /**
     * What the flat discounts and the capped percentages actually take off the subtotal together, shared
     * across the lines in proportion to what each charges.
     */
    private BigDecimal sharedDiscount(BigDecimal subtotal) {
        return discounts.stream().filter(d -> !d.isPercentage() || isCappedAt(subtotal, d)).map(this::discountTakenBy)
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
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
        this.amount = getNetTotal().max(minimumCharge);
    }

    /** Marks this invoice as sent to the client. */
    public void markSent() {
        this.status = InvoiceStatus.SENT;
    }

    /** Cancels this invoice, so it is no longer owed. */
    public void markVoid() {
        this.status = InvoiceStatus.VOID;
    }

    /** Writes this invoice off as bad debt, so it is no longer owed. */
    public void markWrittenOff() {
        this.status = InvoiceStatus.WRITTEN_OFF;
    }

    /** Writes part of this invoice off as bad debt, so that part is no longer owed while the rest still is. */
    public void writeOffPart(BigDecimal part) {
        this.writeOffAmount = writeOffAmount.add(part).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal getWriteOffAmount() {
        return writeOffAmount;
    }

    /**
     * What is left to pay on this invoice given the totals paid and credited against it, less any part written
     * off; nothing once it is void or written off.
     */
    public BigDecimal amountDue(BigDecimal paid, BigDecimal credited) {
        return status.isClosedUnpaid() ? BigDecimal.ZERO.setScale(2)
                : amount.subtract(paid).subtract(credited).subtract(writeOffAmount);
    }

    /**
     * What the client owes on this invoice right now given the totals paid and credited against it: what is
     * left to pay less the retention still held back, never below nothing on account of the retention.
     */
    public BigDecimal dueNow(BigDecimal paid, BigDecimal credited) {
        BigDecimal left = amountDue(paid, credited);
        if (left.signum() <= 0 || status.isClosedUnpaid()) {
            return left;
        }
        return left.subtract(getRetention()).max(BigDecimal.ZERO.setScale(2));
    }

    /**
     * This invoice's status given the totals paid and credited against it. Once sent, it is PAID when the
     * payments, credit notes and any part written off together clear the amount, PARTIAL once something has been
     * paid, credited or written off, otherwise still SENT. Drafts, void and written-off invoices keep their status, as does one marked paid by hand.
     */
    public InvoiceStatus statusFor(BigDecimal paid, BigDecimal credited) {
        if (status == InvoiceStatus.DRAFT || status.isClosedUnpaid()) {
            return status;
        }
        BigDecimal settled = paid.add(credited).add(writeOffAmount);
        if (settled.compareTo(amount) >= 0 || (status == InvoiceStatus.PAID && settled.signum() == 0)) {
            return InvoiceStatus.PAID;
        }
        return settled.signum() > 0 ? InvoiceStatus.PARTIAL : InvoiceStatus.SENT;
    }

    /**
     * Works out this sent invoice's status from what is still owed on it after the totals paid and credited against
     * it and any part written off: PAID once nothing is left, PARTIAL once something has been settled, otherwise
     * still SENT.
     */
    public void applySettledTotals(BigDecimal paid, BigDecimal credited) {
        if (status == InvoiceStatus.DRAFT || status.isClosedUnpaid()) {
            throw new IllegalStateException("A " + status.name().toLowerCase() + " invoice cannot be settled");
        }
        this.status = statusFor(paid, credited);
    }
}
