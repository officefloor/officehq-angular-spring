package net.officefloor.hq.app.invoices;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import net.officefloor.hq.app.TaxRate;
import net.officefloor.hq.app.Today;
import net.officefloor.hq.app.clients.Currency;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InvoiceController {

    /**
     * An invoice as listed: its total is a decimal string to the cent, e.g. "25.25"; its status
     * follows what has been paid and whether it is past due.
     */
    public record InvoiceSummary(Long id, String number, InvoiceStatus status, String total,
            LocalDate issueDate, LocalDate dueDate, Long creditOf) {
        static InvoiceSummary of(Invoice i, BigDecimal taxRate, BigDecimal paid, LocalDate paidOn, LocalDate today) {
            return new InvoiceSummary(i.getId(), i.number(), i.statusWhenPaid(paid, paidOn, taxRate, today),
                    listedTotal(i, taxRate), i.getIssueDate(), i.listedDueDate(paid, taxRate), i.getCreditOf());
        }
    }

    /** The grand total as listed: negative for a credit note. */
    static String listedTotal(Invoice i, BigDecimal taxRate) {
        BigDecimal total = i.total(taxRate);
        return (i.isCreditNote() ? total.negate() : total).toPlainString();
    }

    /**
     * What a client still owes across its sent invoices (drafts do not count), after discounts, tax
     * and payments, less the client's unused credit (so it can be below zero), how much of what is due
     * is overdue, the unused credit, and how much of what is due is in dispute (it does not count against the
     * credit limit), and what its approved drafts will add to the balance once sent (committed: it counts
     * against the credit limit); decimal strings to the cent.
     */
    public record ClientBalance(Long clientId, String balance, String overdue, String credit, String disputed,
            String committed) {
    }

    /**
     * A payment made to an opened invoice: the whole amount paid in the {@code currency} it was paid in,
     * how much of it went to this invoice (to the cent) in the client's currency, its date and the
     * reference it quoted in groups of four (null when none). {@code surcharge} is the part of it that
     * was the card surcharge, in the client's currency ("0.00" unless paid by card), and {@code fxFee} the
     * part that was the conversion fee ("0.00" unless paid in another currency). {@code remittance} is the
     * remittance text (null when none), {@code matchedInvoice} the numbers of the invoices whose purchase order
     * numbers its parts matched and {@code matchScore} their similarities to three decimals, each comma separated
     * in the order of the parts (both null when none).
     */
    public record PaymentView(Long id, String amount, Currency currency, String allocated, LocalDate date,
            String reference, PaymentMethod method, String surcharge, String fxFee, String remittance, String matchedInvoice,
            String matchScore) {
        static PaymentView of(Payment p, Long invoiceId, Currency clientCurrency) {
            return new PaymentView(p.getId(), p.paidAmount().setScale(2).toPlainString(),
                    p.paidCurrency(clientCurrency), p.amountTo(invoiceId).setScale(2).toPlainString(), p.getDate(),
                    p.getReference() == null ? null : PaymentReference.grouped(p.getReference()), p.getMethod(),
                    p.getSurcharge().setScale(2).toPlainString(), p.getFxFee().setScale(2).toPlainString(),
                    p.getRemittance(),
                    p.getMatches().isEmpty() ? null : p.getMatches().stream()
                            .map(m -> InvoiceNumber.of(m.getInvoiceId())).collect(Collectors.joining(", ")),
                    p.getMatches().isEmpty() ? null : p.getMatches().stream()
                            .map(m -> m.getScore().setScale(3).toPlainString()).collect(Collectors.joining(", ")));
        }
    }

    /**
     * One line of an opened invoice. The quantity is as entered, without trailing zeros ("1.5");
     * money is a decimal string to the cent. {@code unitType} is null when the line has none. {@code discountShare} is the line's share of the
     * invoice discount; {@code tax} is the line's tax, the rate on its amount less that share, and
     * {@code taxExempt} lines have none. {@code net} is the line before tax: its amount less that share, less
     * the tax inside it when the client's prices include tax. {@code taxRate} (without trailing zeros) is the line's own
     * tax rate, null when it uses the workspace rate. {@code discountPct}
     * (without trailing zeros) is the line's own discount and {@code discount} what it takes off;
     * {@code amount} is after it. {@code creditOfLine} is the id of the line a credit note's line credits, null when none.
     * {@code tiered} lines are priced in progressive tiers from the rate card; their {@code unitPrice} is the first band's.
     * {@code overtime} lines are charged at time and a half: {@code unitPrice} (and each band's price) is 1.5 times the
     * price given, rounded half up to the cent; {@code basePrice} is the price given, before overtime.
     * {@code billedQty} (without trailing zeros) is the quantity the line is priced on: its quantity rounded up to the next
     * whole increment of its unit type's rate card entry (as entered without one), and then at least its minimum units.
     * {@code periodStart} and {@code periodEnd} are the line's service period (null for none): its quantity times unit price is
     * then a month's price, and its amounts are for the days of the period at 12 / 365 of it a day.
     * {@code percentComplete} (without trailing zeros) is the percentage of a line billed by progress, null for the whole line;
     * {@code billedPct} (without trailing zeros) is the percentage of it already billed by earlier claims, null when none was.
     */
    public record LineView(Long id, String description, String qty, String unitType, String unitPrice, String discountPct,
            String discount, String amount, String discountShare, String tax, String net, boolean taxExempt, String taxRate,
            Long creditOfLine, boolean tiered, String billedQty, boolean overtime, String basePrice,
            LocalDate periodStart, LocalDate periodEnd, boolean disputed, String percentComplete, String billedPct) {
        static LineView of(InvoiceLine l, int scale, BigDecimal discountShare, BigDecimal tax, BigDecimal includedTax,
                boolean inDispute) {
            return new LineView(l.getId(), l.getDescription(), l.getQty().stripTrailingZeros().toPlainString(),
                    l.getUnitType(), l.chargedUnitPrice().setScale(2).toPlainString(),
                    l.getDiscountPct().stripTrailingZeros().toPlainString(), l.discount(scale).toPlainString(),
                    l.amount(scale).toPlainString(), discountShare.toPlainString(), tax.toPlainString(),
                    l.amount(scale).subtract(discountShare).subtract(includedTax).toPlainString(),
                    l.isTaxExempt(),
                    l.getTaxRate() == null ? null : l.getTaxRate().stripTrailingZeros().toPlainString(),
                    l.getCreditOfLine(), l.isTiered(), l.billedQty().stripTrailingZeros().toPlainString(), l.isOvertime(),
                    l.getUnitPrice().setScale(2).toPlainString(), l.getPeriodStart(), l.getPeriodEnd(),
                    inDispute && l.isDisputed(),
                    l.getPercentComplete() == null ? null : l.getPercentComplete().stripTrailingZeros().toPlainString(),
                    l.getBilledPct() == null ? null : l.getBilledPct().stripTrailingZeros().toPlainString());
        }
    }

    /**
     * An opened invoice: its lines, subtotal, discount percentage (without trailing zeros, "12.5"),
     * the amount it takes off, the tax rate (without trailing zeros) and the tax charged, the sum of the
     * line taxes, and the grand total; the payments made to it, what they add up to, what is still due and what was written off because less than
     * $0.05, and no more than 1% of the grand total, was left (money as
     * decimal strings to the cent). Its status follows what has been paid. {@code reference} is its
     * RF payment reference in groups of four. {@code paidDate} is the date of the payment that cleared
     * it, payments taken in date order; null while it is not fully paid. {@code credited} is what
     * issued credit notes take off it; {@code creditOf} the id of the invoice a credit note credits
     * (null for an ordinary invoice) and {@code listedTotal} the total as listed, negative for a credit note.
     * {@code listedDueDate} is the due date as listed: billed by milestones, its next unpaid milestone's. {@code currency} is the
     * currency its client is billed in, which every amount is in (whole yen for a yen client).
     * {@code earlyDiscount} is the early-payment discount it was settled with (0 when none), and
     * {@code settleAmount} what would settle it today while its early-payment window is open and its client is a LOW payment risk (null otherwise).
     * {@code lateInterest} is the late interest charged on it so far, part of the amount due;
     * {@code reminderFees} is the reminder fees charged on it (see {@link Invoice#reminderFees}), part of the amount due;
     * {@code lateWaived} is the late interest written off by waiving it (0 when none was). {@code fxDifference}
     * is, for a client billed in another currency, the exchange difference in US dollars of the payments
     * applied to it (see {@link Invoice#fxDifference(List)}); null for a dollar invoice.
     * {@code writtenOffOn} is the day it was written off as a bad debt (null when it was not); everything
     * it still owed then is in {@code writtenOff}, and nothing is due. {@code net} is the amount before tax; when
     * {@code pricesIncludeTax} the tax is inside the subtotal less the discount, which is then the grand total.
     * {@code cashDue} is the amount due rounded down to the cash coin below (0.05, or whole yen) and
     * {@code cashRounding} the difference (signed): once settled in cash, the cash rounding it was settled with.
     * {@code surchargePct} (without trailing zeros) is the rush surcharge percentage and {@code surcharge} that share of
     * the subtotal less the discount, rounded down to the cent; it counts towards the {@code minimumCharge}.
     * {@code withholding} is what the client withholds for its tax office (0 when none): never owed, so
     * left out of the amount due; nothing when reverse charged. {@code withholdingPct} (without trailing zeros)
     * is the client's withholding percentage. {@code approval} is whether it needs approval before it is sent
     * (see {@link Invoice#approval}), and {@code approvedTotal} the grand total it was approved at and {@code approvedOn} the day (null when it was not);
     * {@code approvalsNeeded} is how many approvals it still needs before it is sent (0 when none) and {@code approvals} how many it has at its total.
     * {@code recurring} is whether it repeats every period, and {@code recurringFrom} the id of the recurring invoice it was generated from (null when it was not);
     * {@code recurUntil} is the last day it bills for when recurring (null for no end), and {@code recurringSince} the day its plan began
     * (moved on a year at each yearly price rise; null for none).
     * {@code claimFrom} is the id of the invoice it was claimed from as its next progress claim (null when it was not).
     * {@code disputedOn} is the day it was last disputed (null when never) and {@code disputeResolvedOn} the day that dispute was resolved (null while it is open);
     * {@code disputed} is the amount in dispute (see {@link Invoice#disputedAmount}), 0 when none is.
     * {@code poNumber} is the client's purchase order number (null when none).
     * {@code sentTotal} is the grand total it was sent with, which it is worth (null for a draft, or when not kept), and
     * {@code currentTotal} the grand total today's prices, rates and client details would give, only when that differs (else null).
     * {@code rebilledOn} is the day it was rebilled (null when it was not); {@code replaces} is the number of the rebilled
     * invoice it replaces and {@code replacedBy} the number of the invoice that replaces it (each null when none).
     * {@code adjustmentOf} is the id of the sent invoice it is an adjustment note of (null when it is not) and {@code adjusted}
     * what the adjustment notes issued against it add up to, a credit note's negative (0 when none).
     * {@code milestones} are the milestones it is billed by (empty when it is billed whole), each with its
     * amount and status (see {@link Invoice#milestoneProgress}); {@code milestonesPct} is what their percentages add up to.
     */
    public record InvoiceDetail(Long id, String number, String reference, Long clientId, InvoiceStatus status, LocalDate issueDate,
            LocalDate dueDate, List<LineView> lines, String subtotal, String discountPct, String discountAmount,
            String discountCap, String discount, String discountReason, String taxRate, String tax, String total, String net, boolean pricesIncludeTax, List<PaymentView> payments, String paid, String amountDue, String writtenOff, LocalDate paidDate, boolean manualDueDate,
            String credited, Long creditOf, String listedTotal, LocalDate listedDueDate,
            Currency currency, String earlyDiscount, String settleAmount, String lateInterest, String reminderFees, String lateWaived,
            String fxDifference, LocalDate writtenOffOn, boolean reverseCharge,
            String minimumCharge, String cashDue, String cashRounding, String surchargePct, String surcharge, String withholding,
            String withholdingPct, String supplyRegion, Invoice.Approval approval, String approvedTotal, LocalDate approvedOn,
            int approvalsNeeded, int approvals,
            boolean recurring, Long recurringFrom, LocalDate recurUntil, LocalDate recurringSince, LocalDate disputedOn, LocalDate disputeResolvedOn, String disputed, String poNumber,
            List<MilestoneView> milestones, String milestonesPct, Long claimFrom, String sentTotal, String currentTotal,
            String fingerprint, String duplicateOf, LocalDate rebilledOn, String replaces, String replacedBy,
            Long adjustmentOf, String adjusted, DunningLevel dunning, List<LocalDate> reminders) {
        static InvoiceDetail of(Invoice i, BigDecimal taxRate, List<Payment> payments, LocalDate today, Invoice.Approval approval,
                List<Invoice> duplicates) {
            BigDecimal paid = payments.stream().map(p -> p.amountTo(i.getId()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
            LocalDate paidOn = i.paidOn(payments, taxRate);
            List<BigDecimal> shares = i.discountShares();
            List<BigDecimal> taxes = i.lineTaxes(taxRate);
            List<BigDecimal> inside = i.lineTaxesInside(taxRate);
            List<LineView> lines = IntStream.range(0, shares.size())
                    .mapToObj(n -> LineView.of(i.getLines().get(n), i.scale(), shares.get(n), taxes.get(n),
                            inside.get(n), i.isDisputed())).toList();
            InvoiceStatus status = i.statusWhenPaid(paid, paidOn, taxRate, today);
            return new InvoiceDetail(i.getId(), i.number(),
                    PaymentReference.grouped(PaymentReference.of(i.getId())), i.getClientId(),
                    status, i.getIssueDate(), i.getDueDate(),
                    lines, i.subtotal().toPlainString(),
                    i.getDiscountPct().stripTrailingZeros().toPlainString(),
                    i.getDiscountAmount().setScale(2).toPlainString(),
                    i.getDiscountCap() == null ? null : i.getDiscountCap().setScale(2).toPlainString(),
                    i.discount().toPlainString(), i.getDiscountReason(),
                    i.clientTaxRate(taxRate).stripTrailingZeros().toPlainString(), i.tax(taxRate).toPlainString(),
                    i.total(taxRate).toPlainString(), i.beforeTax(taxRate).toPlainString(), i.isPricesIncludeTax(),
                    payments.stream().map(p -> PaymentView.of(p, i.getId(), i.currency())).toList(), paid.toPlainString(),
                    i.amountDue(paid, taxRate).toPlainString(), i.writtenOff(paid, taxRate).toPlainString(), paidOn,
                    i.isManualDueDate(), i.getCredited().toPlainString(), i.getCreditOf(), InvoiceController.listedTotal(i, taxRate),
                    i.listedDueDate(paid, taxRate),
                    i.currency(), i.getEarlyDiscountTaken().toPlainString(),
                    Optional.ofNullable(i.settleAmount(paid, taxRate, today)).map(BigDecimal::toPlainString).orElse(null),
                    i.getLateInterest().toPlainString(),
                    i.getReminderFees().toPlainString(),
                    i.getLateWaived().toPlainString(),
                    i.currency() == Currency.USD ? null : i.fxDifference(payments).toPlainString(),
                    i.getWrittenOffOn(), i.isReverseCharge(), i.minimumCharge().toPlainString(),
                    i.cashDue(paid, taxRate).toPlainString(), i.cashRounding(paid, taxRate).toPlainString(),
                    i.chargedSurchargePct().stripTrailingZeros().toPlainString(), i.surcharge().toPlainString(),
                    i.withholding(taxRate).toPlainString(), i.getWithholdingPct().stripTrailingZeros().toPlainString(),
                    i.getSupplyRegion(), approval,
                    i.getApprovedTotal() == null ? null : i.getApprovedTotal().toPlainString(), i.getApprovedOn(),
                    i.approvalsNeeded(approval, taxRate, today), i.approvalsGiven(taxRate, today),
                    i.isRecurring(), i.getRecurringFrom(), i.getRecurUntil(), i.getRecurringSince(), i.getDisputedOn(), i.getDisputeResolvedOn(),
                    i.disputedAmount(paid, taxRate).toPlainString(), i.getPoNumber(),
                    i.milestoneProgress(paid, taxRate, today).stream().map(MilestoneView::of).toList(),
                    i.milestonesPct().stripTrailingZeros().toPlainString(), i.getClaimFrom(),
                    i.hasSentFigures() ? i.total(taxRate).toPlainString() : null,
                    i.hasSentFigures() && i.currentTotal(taxRate).compareTo(i.total(taxRate)) != 0
                            ? i.currentTotal(taxRate).toPlainString() : null,
                    Optional.ofNullable(i.fingerprint()).map(f -> f.substring(0, 8)).orElse(null),
                    duplicates.isEmpty() ? null
                            : duplicates.stream().map(Invoice::number).collect(java.util.stream.Collectors.joining(", ")),
                    i.getRebilledOn(), i.getReplaces() == null ? null : InvoiceNumber.of(i.getReplaces()),
                    i.getReplacedBy() == null ? null : InvoiceNumber.of(i.getReplacedBy()),
                    i.getAdjustmentOf(), i.getAdjusted().toPlainString(), i.dunningLevel(status, i.overdueAmount(paid, taxRate, today), today), i.getReminders());
        }
    }

    /**
     * A discount to give: a percentage from 0 to 100 with at most two decimals, or a fixed amount
     * of money, not negative and to the cent. Exactly one of them. Optionally a cap, money not
     * negative and to the cent, that everything taken off, line discounts included, never comes to more than.
     * Optionally a reason.
     */
    public record NewDiscount(
            @DecimalMin(value = "0", message = "The discount must be from 0 to 100 percent.")
            @DecimalMax(value = "100", message = "The discount must be from 0 to 100 percent.")
            @Digits(integer = 3, fraction = 2, message = "The discount can have at most two decimals.")
            BigDecimal discountPct,
            @DecimalMin(value = "0", message = "The discount amount cannot be negative.")
            @Digits(integer = 10, fraction = 2, message = "The discount amount can have at most two decimals.")
            BigDecimal discountAmount,
            @DecimalMin(value = "0", message = "The discount cap cannot be negative.")
            @Digits(integer = 10, fraction = 2, message = "The discount cap can have at most two decimals.")
            BigDecimal discountCap,
            @Size(max = 500, message = "The discount reason can be at most 500 characters.")
            String discountReason) {

        @AssertTrue(message = "Enter either a discount percentage or a discount amount.")
        public boolean isDiscountGiven() {
            return (discountPct == null) != (discountAmount == null);
        }
    }

    /**
     * A line to add: a description, a quantity above zero, a unit type (none when left out or blank)
     * and a unit price of at least $0.01 (the client's rate for the unit type when left out), and
     * whether it is tax exempt (taxable when left out), and its own discount percentage from 0 to 100
     * with at most two decimals (none when left out), and its own tax rate from 0 to 100 with at most
     * two decimals (the workspace rate when left out).
     */
    public record NewLine(
            @NotBlank(message = "Enter a description.")
            @Size(max = 255, message = "The description must be at most 255 characters.")
            String description,
            @NotNull(message = "Enter a quantity.")
            @DecimalMin(value = "0", inclusive = false, message = "The quantity must be more than zero.")
            @Digits(integer = 10, fraction = 2, message = "The quantity can have at most two decimals.")
            BigDecimal qty,
            @Size(max = 64, message = "The unit type must be at most 64 characters.")
            String unitType,
            @DecimalMin(value = "0.01", message = "The unit price must be at least $0.01.")
            @Digits(integer = 17, fraction = 2, message = "The unit price can have at most two decimals.")
            BigDecimal unitPrice,
            Boolean overtime,
            Boolean taxExempt,
            @DecimalMin(value = "0", message = "The line discount must be from 0 to 100 percent.")
            @DecimalMax(value = "100", message = "The line discount must be from 0 to 100 percent.")
            @Digits(integer = 3, fraction = 2, message = "The line discount can have at most two decimals.")
            BigDecimal discountPct,
            @DecimalMin(value = "0", message = "The line tax rate must be from 0 to 100 percent.")
            @DecimalMax(value = "100", message = "The line tax rate must be from 0 to 100 percent.")
            @Digits(integer = 3, fraction = 2, message = "The line tax rate can have at most two decimals.")
            BigDecimal taxRate,
            // The service period the line is billed for, both ends included; none when both are left out.
            LocalDate periodStart,
            LocalDate periodEnd,
            // The percentage of the work done, for a line billed by progress; the whole line when left out.
            @DecimalMin(value = "0", inclusive = false, message = "The percent complete must be more than 0 and at most 100.")
            @DecimalMax(value = "100", message = "The percent complete must be more than 0 and at most 100.")
            @Digits(integer = 3, fraction = 2, message = "The percent complete can have at most two decimals.")
            BigDecimal percentComplete) {

        @AssertTrue(message = "Enter both the start and the end of the service period, or neither.")
        public boolean isPeriodComplete() {
            return (periodStart == null) == (periodEnd == null);
        }

        @AssertTrue(message = "The service period cannot end before it starts.")
        public boolean isPeriodInOrder() {
            return periodStart == null || periodEnd == null || !periodEnd.isBefore(periodStart);
        }
    }

    /**
     * A payment to record: an amount more than zero with at most two decimals, the date it was paid,
     * and the reference the client quoted (optional).
     */
    public record NewPayment(
            @NotNull(message = "Enter an amount.")
            @DecimalMin(value = "0", inclusive = false, message = "The amount must be more than zero.")
            @Digits(integer = 17, fraction = 2, message = "The amount can have at most two decimals.")
            BigDecimal amount,
            // The currency it was paid in; the client's own when left out.
            Currency currency,
            @NotNull(message = "Enter the date it was paid.")
            LocalDate date,
            @Size(max = 64, message = "The reference must be at most 64 characters.")
            String reference,
            // How it was paid; a bank transfer when left out.
            PaymentMethod method,
            @Size(max = 255, message = "The remittance text must be at most 255 characters.")
            String remittance) {
    }

    /**
     * A deposit to hold as credit: an amount more than zero with at most two decimals, the date it was
     * paid, and optionally the reference of the client's draft it is held for.
     */
    public record NewDeposit(
            @NotNull(message = "Enter an amount.")
            @DecimalMin(value = "0", inclusive = false, message = "The amount must be more than zero.")
            @Digits(integer = 17, fraction = 2, message = "The amount can have at most two decimals.")
            BigDecimal amount,
            // The currency it was paid in; the client's own when left out.
            Currency currency,
            @NotNull(message = "Enter the date it was paid.")
            LocalDate date,
            // The reference or invoice number of the client's draft to hold it for; none when left out.
            @Size(max = 64, message = "The reference must be at most 64 characters.")
            String reference,
            // How it was paid; a bank transfer when left out. Cash is taken only against an open invoice.
            PaymentMethod method,
            // The client's remittance text: with no reference, it first pays the invoice whose purchase order is most like it.
            @Size(max = 255, message = "The remittance text must be at most 255 characters.")
            String remittance) {
    }

    /**
     * A refund of unused credit: an amount more than zero with at most two decimals, the currency it is
     * refunded in, and the date it was refunded.
     */
    /** A rate for a client's rate card: a unit type and a price more than zero with at most two decimals. */
    public record NewRate(
            @NotBlank(message = "Enter the unit type.")
            @Size(max = 64, message = "The unit type must be at most 64 characters.")
            String unitType,
            @NotNull(message = "Enter the price.")
            @DecimalMin(value = "0", inclusive = false, message = "The price must be more than zero.")
            @Digits(integer = 17, fraction = 2, message = "The price can have at most two decimals.")
            BigDecimal unitPrice) {
    }

    public record NewRefund(
            @NotNull(message = "Enter an amount.")
            @DecimalMin(value = "0", inclusive = false, message = "The amount must be more than zero.")
            @Digits(integer = 17, fraction = 2, message = "The amount can have at most two decimals.")
            BigDecimal amount,
            // The currency it is refunded in; the client's own when left out.
            Currency currency,
            @NotNull(message = "Enter the date it was refunded.")
            LocalDate date) {
    }

    /**
     * One milestone of an invoice: its id, description, percentage (without trailing zeros), the day it was
     * reached and the day it falls due (null while pending), its amount, what has been paid off it, the late interest
     * it has been charged and what of that is unpaid, decimal strings to the cent; its status, and the day it was
     * disputed (null when it is not in dispute).
     */
    public record MilestoneView(Long id, String description, String pct, LocalDate reachedOn, LocalDate dueDate,
            String amount, String paid, String lateInterest, String interestUnpaid, Milestone.Status status,
            LocalDate disputedOn) {
        static MilestoneView of(Milestone.Progress p) {
            Milestone m = p.milestone();
            return new MilestoneView(m.getId(), m.getDescription(), m.getPct().stripTrailingZeros().toPlainString(),
                    m.getReachedOn(), p.dueDate(), p.amount().toPlainString(), p.paid().toPlainString(),
                    p.lateInterest().toPlainString(), p.interestUnpaid().toPlainString(), p.status(),
                    m.getDisputedOn());
        }
    }

    /** A milestone to add to a draft: a description and a percentage over 0 and at most 100 with at most two decimals. */
    public record NewMilestone(
            @NotBlank(message = "Enter a description.")
            @Size(max = 255, message = "The description must be at most 255 characters.")
            String description,
            @NotNull(message = "Enter a percentage.")
            @DecimalMin(value = "0", inclusive = false, message = "The percentage must be more than 0 and at most 100.")
            @DecimalMax(value = "100", message = "The percentage must be more than 0 and at most 100.")
            @Digits(integer = 3, fraction = 2, message = "The percentage can have at most two decimals.")
            BigDecimal pct) {
    }

    /** A rush surcharge on a draft: a percentage from 0 to 25 with at most two decimals. */
    public record NewSurcharge(
            @NotNull(message = "Enter a surcharge percentage.")
            @DecimalMin(value = "0", message = "The surcharge must be from 0 to 25 percent.")
            @DecimalMax(value = "25", message = "The surcharge must be from 0 to 25 percent.")
            @Digits(integer = 2, fraction = 2, message = "The surcharge can have at most two decimals.")
            BigDecimal surchargePct) {
    }

    /** A due date to set on a draft by hand. */
    public record NewDueDate(
            @NotNull(message = "Enter the due date.")
            LocalDate dueDate) {
    }

    /** A payment to spread automatically over several of a client's invoices. */
    public record NewSplitPayment(
            @NotNull(message = "Enter an amount.")
            @DecimalMin(value = "0", inclusive = false, message = "The amount must be more than zero.")
            @Digits(integer = 17, fraction = 2, message = "The amount can have at most two decimals.")
            BigDecimal amount,
            // The currency it was paid in; the client's own when left out.
            Currency currency,
            @NotNull(message = "Enter the date it was paid.")
            LocalDate date,
            @Size(max = 255, message = "The references must be at most 255 characters.")
            String reference,
            // How it was paid; a bank transfer when left out. Cash is taken only against an open invoice.
            PaymentMethod method,
            // The client's remittance text: with no reference, it first pays the invoice whose purchase order is most like it.
            @Size(max = 255, message = "The remittance text must be at most 255 characters.")
            String remittance) {
    }

    private final InvoiceService invoices;
    private final TaxRate taxRate;
    private final Today today;

    public InvoiceController(InvoiceService invoices, TaxRate taxRate, Today today) {
        this.invoices = invoices;
        this.taxRate = taxRate;
        this.today = today;
    }

    private InvoiceSummary summary(Invoice i) {
        return InvoiceSummary.of(i, taxRate.percent(), invoices.paid(List.of(i)).get(i.getId()),
                invoices.paidOn(List.of(i)).get(i.getId()), today.date());
    }

    private InvoiceDetail detail(Invoice i) {
        invoices.adjustFingerprint(i);
        return InvoiceDetail.of(i, taxRate.percent(), invoices.payments(i.getId()), today.date(), invoices.approval(i),
                invoices.possibleDuplicateOf(i));
    }

    @GetMapping("/api/clients/{clientId}/invoices")
    public List<InvoiceSummary> forClient(@PathVariable Long clientId) {
        BigDecimal rate = taxRate.percent();
        List<Invoice> list = invoices.forClient(clientId);
        Map<Long, BigDecimal> paid = invoices.paid(list);
        Map<Long, LocalDate> paidOn = invoices.paidOn(list);
        LocalDate on = today.date();
        return list.stream().map(i -> InvoiceSummary.of(i, rate, paid.get(i.getId()), paidOn.get(i.getId()), on)).toList();
    }

    /**
     * The balance of each client that has a sent invoice, an approved draft or credit; a client not listed owes nothing,
     * has no credit and nothing committed. Void invoices and credit notes do not count (a credit note comes off its original).
     */
    @GetMapping("/api/clients/balances")
    public List<ClientBalance> balances() {
        BigDecimal rate = taxRate.percent();
        List<Invoice> sent = invoices.all().stream().filter(i -> !i.isDraft() && !i.isVoid() && !i.isCreditNote()).toList();
        Map<Long, BigDecimal> paid = invoices.paid(sent);
        LocalDate on = today.date();
        Map<Long, BigDecimal[]> owed = new TreeMap<>();
        for (Invoice i : sent) {
            BigDecimal due = i.amountDue(paid.get(i.getId()), rate);
            BigDecimal[] sums = owed.computeIfAbsent(i.getClientId(),
                    k -> new BigDecimal[] { BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2) });
            sums[0] = sums[0].add(due);
            sums[1] = sums[1].add(i.overdueAmount(paid.get(i.getId()), rate, on));
            sums[2] = sums[2].add(i.disputedAmount(paid.get(i.getId()), rate));
        }
        Map<Long, BigDecimal> credits = invoices.credits();
        invoices.all().stream().filter(i -> i.isDraft() && !i.isCreditNote()).map(Invoice::getClientId).distinct()
                .filter(clientId -> invoices.committed(clientId, null).signum() > 0)
                .forEach(clientId -> owed.computeIfAbsent(clientId,
                        k -> new BigDecimal[] { BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2) }));
        credits.keySet().forEach(clientId -> owed.computeIfAbsent(clientId,
                k -> new BigDecimal[] { BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2) }));
        return owed.entrySet().stream().map(e -> {
            BigDecimal credit = credits.getOrDefault(e.getKey(), BigDecimal.ZERO).setScale(2);
            return new ClientBalance(e.getKey(), e.getValue()[0].subtract(credit).toPlainString(),
                    e.getValue()[1].toPlainString(), credit.toPlainString(), e.getValue()[2].toPlainString(),
                    invoices.committed(e.getKey(), null).toPlainString());
        }).toList();
    }

    @PostMapping("/api/clients/{clientId}/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceSummary newDraft(@PathVariable Long clientId) {
        return invoices.newDraft(clientId).map(this::summary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such client."));
    }

    /** Sends every draft of the client that has a line; answers the client's invoices with their new statuses. */
    @PostMapping("/api/clients/{clientId}/invoices/send-drafts")
    public List<InvoiceSummary> sendAllDrafts(@PathVariable Long clientId) {
        invoices.sendAllDrafts(clientId);
        return forClient(clientId);
    }

    @GetMapping("/api/invoices/{id}")
    public InvoiceDetail open(@PathVariable Long id) {
        return invoices.find(id).map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Opens the invoice with a typed number; a number whose check digit is wrong is refused. */
    @GetMapping("/api/invoices/by-number/{number}")
    public InvoiceDetail openByNumber(@PathVariable String number) {
        Long id = InvoiceNumber.idOf(number).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, number.trim() + " is mistyped: check the invoice number."));
        return invoices.find(id).filter(i -> i.number().equals(number.trim().toUpperCase())).map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "There is no invoice " + number.trim() + "."));
    }

    /** The line's unit type, trimmed; null when left out or blank. */
    private static String unitType(NewLine body) {
        return body.unitType() == null || body.unitType().isBlank() ? null : body.unitType().trim();
    }

    @PostMapping("/api/invoices/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetail addLine(@PathVariable Long id, @Valid @RequestBody NewLine body) {
        return invoices.addLine(id, body.description().trim(), body.qty(), unitType(body), body.unitPrice(),
                Boolean.TRUE.equals(body.overtime()), Boolean.TRUE.equals(body.taxExempt()),
                body.discountPct() == null ? BigDecimal.ZERO : body.discountPct(), body.taxRate(),
                body.periodStart(), body.periodEnd(), body.percentComplete())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    @PutMapping("/api/invoices/{id}/lines/{lineId}")
    public InvoiceDetail updateLine(@PathVariable Long id, @PathVariable Long lineId,
            @Valid @RequestBody NewLine body) {
        return invoices.updateLine(id, lineId, body.description().trim(), body.qty(), unitType(body), body.unitPrice(),
                Boolean.TRUE.equals(body.overtime()), Boolean.TRUE.equals(body.taxExempt()),
                body.discountPct() == null ? BigDecimal.ZERO : body.discountPct(), body.taxRate(),
                body.periodStart(), body.periodEnd(), body.percentComplete())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such line."));
    }

    @DeleteMapping("/api/invoices/{id}/lines/{lineId}")
    public InvoiceDetail removeLine(@PathVariable Long id, @PathVariable Long lineId) {
        return invoices.removeLine(id, lineId)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such line."));
    }

    @PutMapping("/api/invoices/{id}/discount")
    public InvoiceDetail setDiscount(@PathVariable Long id, @Valid @RequestBody NewDiscount body) {
        return invoices.setDiscount(id, body.discountPct(), body.discountAmount(), body.discountCap(), body.discountReason())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    @PutMapping("/api/invoices/{id}/surcharge")
    public InvoiceDetail setSurcharge(@PathVariable Long id, @Valid @RequestBody NewSurcharge body) {
        return invoices.setSurcharge(id, body.surchargePct())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    @PostMapping("/api/invoices/{id}/milestones")
    public InvoiceDetail addMilestone(@PathVariable Long id, @Valid @RequestBody NewMilestone body) {
        return invoices.addMilestone(id, body.description().trim(), body.pct())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Marks a milestone of a sent invoice reached: it is owed from today. */
    @PostMapping("/api/invoices/{id}/milestones/{milestoneId}/reach")
    public InvoiceDetail reachMilestone(@PathVariable Long id, @PathVariable Long milestoneId) {
        return invoices.reachMilestone(id, milestoneId)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Puts a reached milestone of a sent invoice in dispute. */
    @PostMapping("/api/invoices/{id}/milestones/{milestoneId}/dispute")
    public InvoiceDetail disputeMilestone(@PathVariable Long id, @PathVariable Long milestoneId) {
        return invoices.disputeMilestone(id, milestoneId)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Writes off everything still owed on a sent invoice as a bad debt. */
    @PostMapping("/api/invoices/{id}/write-off")
    public InvoiceDetail writeOff(@PathVariable Long id) {
        return invoices.writeOff(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Puts a sent invoice that still has something due in dispute. */
    @PostMapping("/api/invoices/{id}/dispute")
    public InvoiceDetail dispute(@PathVariable Long id) {
        return invoices.dispute(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Puts one line of a sent invoice in dispute. */
    @PostMapping("/api/invoices/{id}/lines/{lineId}/dispute")
    public InvoiceDetail disputeLine(@PathVariable Long id, @PathVariable Long lineId) {
        return invoices.disputeLine(id, lineId)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Ends the dispute on an invoice. */
    @PostMapping("/api/invoices/{id}/resolve-dispute")
    public InvoiceDetail resolveDispute(@PathVariable Long id) {
        return invoices.resolveDispute(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Ends the dispute on an invoice with a credit note for what was in dispute. */
    @PostMapping("/api/invoices/{id}/resolve-dispute-credit")
    public InvoiceDetail resolveDisputeWithCredit(@PathVariable Long id) {
        return invoices.resolveDisputeWithCredit(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** A promise to pay an amount on an overdue invoice by a date. */
    public record NewPromise(
            @NotNull(message = "Enter the amount promised.")
            @Positive(message = "The amount promised must be more than zero.")
            BigDecimal amount,
            @NotNull(message = "Enter the date the payment is promised by.")
            LocalDate date) {
    }

    /** Records a promise to pay on an overdue invoice. */
    @PostMapping("/api/invoices/{id}/promise")
    public InvoiceDetail promise(@PathVariable Long id, @Valid @RequestBody NewPromise body) {
        return invoices.promisePayment(id, body.amount(), body.date())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Sends the dunning reminder for the level an overdue invoice has reached. */
    @PostMapping("/api/invoices/{id}/remind")
    public InvoiceDetail remind(@PathVariable Long id) {
        return invoices.remind(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Waives the late interest still unpaid on an overdue invoice. */
    @PostMapping("/api/invoices/{id}/waive-interest")
    public InvoiceDetail waiveInterest(@PathVariable Long id) {
        return invoices.waiveInterest(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    @PutMapping("/api/invoices/{id}/due-date")
    public InvoiceDetail setDueDate(@PathVariable Long id, @Valid @RequestBody NewDueDate body) {
        return invoices.setDueDate(id, body.dueDate())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** The client's purchase order number for an invoice; blank for none. */
    public record NewPoNumber(
            @Size(max = 100, message = "The purchase order number can be at most 100 characters.") String poNumber) {
    }

    @PutMapping("/api/invoices/{id}/po-number")
    public InvoiceDetail setPoNumber(@PathVariable Long id, @Valid @RequestBody NewPoNumber body) {
        return invoices.setPoNumber(id, body.poNumber())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** The region where a draft's work is done; blank for none, so the client's region applies. */
    public record NewSupplyRegion(
            @Size(max = 50, message = "The region can be at most 50 characters.") String supplyRegion) {
    }

    @PutMapping("/api/invoices/{id}/supply-region")
    public InvoiceDetail setSupplyRegion(@PathVariable Long id, @Valid @RequestBody NewSupplyRegion body) {
        return invoices.setSupplyRegion(id, body.supplyRegion())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    @PostMapping("/api/invoices/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetail duplicate(@PathVariable Long id) {
        return invoices.duplicate(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Whether the invoice repeats every period. */
    public record NewRecurring(@NotNull(message = "Say whether the invoice is recurring.") Boolean recurring) {
    }

    @PutMapping("/api/invoices/{id}/recurring")
    public InvoiceDetail setRecurring(@PathVariable Long id, @Valid @RequestBody NewRecurring body) {
        return invoices.setRecurring(id, body.recurring())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** The last day a recurring invoice bills for; null for no end. */
    public record NewRecurUntil(LocalDate recurUntil) {
    }

    @PutMapping("/api/invoices/{id}/recur-until")
    public InvoiceDetail setRecurUntil(@PathVariable Long id, @RequestBody NewRecurUntil body) {
        return invoices.setRecurUntil(id, body.recurUntil())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /**
     * Rebills a sent invoice: credits it in full and starts a corrected draft replacing it; answers the rebilled invoice.
     */
    @PostMapping("/api/invoices/{id}/rebill")
    public InvoiceDetail rebill(@PathVariable Long id) {
        return invoices.rebill(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Brings a sent invoice up to date: today's grand total and tax become what it was sent with; answers the invoice. */
    @PostMapping("/api/invoices/{id}/update")
    public InvoiceDetail updateSentFigures(@PathVariable Long id) {
        return invoices.updateSentFigures(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Generates the next period's draft of a sent recurring invoice; answers the new draft. */
    @PostMapping("/api/invoices/{id}/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetail generate(@PathVariable Long id) {
        return invoices.generateNext(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Makes the next progress claim of a sent invoice billed by percent complete; answers the new draft. */
    @PostMapping("/api/invoices/{id}/next-claim")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetail nextClaim(@PathVariable Long id) {
        return invoices.nextClaim(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Starts a draft credit note against a sent invoice; answers the new draft. */
    @PostMapping("/api/invoices/{id}/credit-note")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetail creditNote(@PathVariable Long id) {
        return invoices.creditNote(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /**
     * Credits a line of a sent invoice: adds a copy of it to the invoice's draft credit note, starting
     * one when there is none; answers that draft.
     */
    @PostMapping("/api/invoices/{id}/lines/{lineId}/credit")
    public InvoiceDetail creditLine(@PathVariable Long id, @PathVariable Long lineId) {
        return invoices.creditLine(id, lineId)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such line."));
    }

    /** Approves a draft whose grand total is over the workspace's approval limit, or whose discount is more than a fifth. */
    @PostMapping("/api/invoices/{id}/approve")
    public InvoiceDetail approve(@PathVariable Long id) {
        return invoices.approve(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    @PostMapping("/api/invoices/{id}/send")
    public InvoiceDetail send(@PathVariable Long id) {
        return invoices.send(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    @PostMapping("/api/invoices/{id}/void")
    public InvoiceDetail voidInvoice(@PathVariable Long id) {
        return invoices.voidInvoice(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /**
     * Records one payment spread automatically over several of the client's invoices: applied to the invoices whose references are quoted (several may be, separated by commas) or, with none,
     * to all its unpaid invoices, overdue first (earliest due date first), then the smallest amount due first; answers the client's invoices
     * with their new statuses.
     */
    @PostMapping("/api/clients/{clientId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public List<InvoiceSummary> recordSplitPayment(@PathVariable Long clientId, @Valid @RequestBody NewSplitPayment body) {
        refuseCashWithoutInvoice(body.method());
        invoices.recordAutoPayment(clientId, body.amount(), body.currency(), body.date(), references(body.reference()),
                body.method(), body.remittance());
        return forClient(clientId);
    }

    /** Cash is taken only against the invoice that is open, so never spread over invoices or held as credit. */
    private static void refuseCashWithoutInvoice(PaymentMethod method) {
        if (method == PaymentMethod.CASH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Open the invoice the cash is paid against.");
        }
    }

    /**
     * The quoted reference without spaces, or null when none was; a mistyped one is refused. An
     * invoice number quoted instead (INV-0000075, any capitals) stands for that invoice's RF reference.
     */
    private static String reference(String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        Optional<String> parsed = typed.trim().toUpperCase().startsWith("INV")
                ? InvoiceNumber.idOf(typed).map(PaymentReference::of)
                : PaymentReference.parse(typed);
        return parsed.orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, typed.trim() + " is mistyped: check the payment reference."));
    }

    /**
     * The references quoted, separated by commas, each without spaces and in the order typed (an
     * invoice number standing for its RF reference), each with the amount stated after an equals sign
     * (INV-0000018=50) or none; empty when none was. A mistyped reference, or an amount that is not a
     * number with at most two decimals, is refused.
     */
    private static List<QuotedReference> references(String typed) {
        if (typed == null || typed.isBlank()) {
            return List.of();
        }
        return Arrays.stream(typed.split(","))
                .filter(part -> !part.isBlank())
                .map(InvoiceController::quoted)
                .distinct()
                .toList();
    }

    private static QuotedReference quoted(String part) {
        int equals = part.indexOf('=');
        if (equals < 0) {
            return new QuotedReference(reference(part), null);
        }
        String reference = reference(part.substring(0, equals));
        if (reference == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quote a reference before the amount in "
                    + part.trim() + ".");
        }
        String stated = part.substring(equals + 1).replaceAll("\\s", "").replaceFirst("^\\$", "");
        if (!stated.matches("-?\\d+(\\.\\d+)?")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The amount in " + part.trim()
                    + " must be a number.");
        }
        BigDecimal amount = new BigDecimal(stated);
        if (amount.scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The amount in " + part.trim()
                    + " can have at most two decimals.");
        }
        return new QuotedReference(reference, amount);
    }

    /** Records a deposit held on the client's account as credit; answers the client's invoices. */
    @PostMapping("/api/clients/{clientId}/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    public List<InvoiceSummary> recordDeposit(@PathVariable Long clientId, @Valid @RequestBody NewDeposit body) {
        refuseCashWithoutInvoice(body.method());
        invoices.recordDeposit(clientId, body.amount(), body.currency(), body.date(), reference(body.reference()),
                body.method())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such client."));
        return forClient(clientId);
    }

    /** Refunds the client's unused credit, oldest payment's first; answers the client's invoices. */
    @PostMapping("/api/clients/{clientId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public List<InvoiceSummary> refundCredit(@PathVariable Long clientId, @Valid @RequestBody NewRefund body) {
        invoices.refundCredit(clientId, body.amount(), body.currency(), body.date())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such client."));
        return forClient(clientId);
    }

    /**
     * Adds the rate to the client's rate card, or replaces the price for its unit type, repricing the
     * client's drafts priced from the card; answers the client's invoices.
     */
    @PutMapping("/api/clients/{clientId}/rates")
    public List<InvoiceSummary> setRate(@PathVariable Long clientId, @Valid @RequestBody NewRate body) {
        invoices.setRate(clientId, body.unitType(), body.unitPrice())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such client."));
        return forClient(clientId);
    }

    /** Applies the client's unused credit now to what they owe, in the payment order; answers the client's invoices. */
    @PostMapping("/api/clients/{clientId}/apply-credit")
    public List<InvoiceSummary> applyCredit(@PathVariable Long clientId) {
        invoices.applyCreditNow(clientId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such client."));
        return forClient(clientId);
    }

    @PostMapping("/api/invoices/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetail recordPayment(@PathVariable Long id, @Valid @RequestBody NewPayment body) {
        return invoices.recordPayment(id, body.amount(), body.currency(), body.date(), reference(body.reference()),
                body.method(), body.remittance())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Reverses a payment on every invoice it was applied to; answers the invoice it was reversed from. */
    @PostMapping("/api/invoices/{id}/payments/{paymentId}/reverse")
    public InvoiceDetail reversePayment(@PathVariable Long id, @PathVariable Long paymentId) {
        invoices.reversePayment(paymentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such payment."));
        return invoices.find(id).map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }
}
