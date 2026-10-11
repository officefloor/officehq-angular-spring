package net.officefloor.hq.app.invoices;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
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
     * and payments, retention included whether held or released, less the client's unused credit (so it can be below zero), how much of what is due
     * is overdue, and the unused credit; decimal strings to the cent.
     */
    public record ClientBalance(Long clientId, String balance, String overdue, String credit) {
    }

    /**
     * A payment made to an opened invoice: the whole amount paid in the {@code currency} it was paid in,
     * how much of it went to this invoice (to the cent) in the client's currency, its date and the
     * reference it quoted in groups of four (null when none).
     */
    public record PaymentView(Long id, String amount, Currency currency, String allocated, LocalDate date,
            String reference) {
        static PaymentView of(Payment p, Long invoiceId, Currency clientCurrency) {
            return new PaymentView(p.getId(), p.paidAmount().setScale(2).toPlainString(),
                    p.paidCurrency(clientCurrency), p.amountTo(invoiceId).setScale(2).toPlainString(), p.getDate(),
                    p.getReference() == null ? null : PaymentReference.grouped(p.getReference()));
        }
    }

    /**
     * One line of an opened invoice. The quantity is as entered, without trailing zeros ("1.5");
     * money is a decimal string to the cent. {@code discountShare} is the line's share of the
     * invoice discount; {@code tax} is the line's tax, the rate on its amount less that share, and
     * {@code taxExempt} lines have none. {@code taxRate} (without trailing zeros) is the line's own
     * tax rate, null when it uses the workspace rate. {@code discountPct}
     * (without trailing zeros) is the line's own discount and {@code discount} what it takes off;
     * {@code amount} is after it.
     */
    public record LineView(Long id, String description, String qty, String unitPrice, String discountPct,
            String discount, String amount, String discountShare, String tax, boolean taxExempt, String taxRate) {
        static LineView of(InvoiceLine l, int scale, BigDecimal discountShare, BigDecimal tax) {
            return new LineView(l.getId(), l.getDescription(), l.getQty().stripTrailingZeros().toPlainString(),
                    l.getUnitPrice().setScale(2).toPlainString(),
                    l.getDiscountPct().stripTrailingZeros().toPlainString(), l.discount(scale).toPlainString(),
                    l.amount(scale).toPlainString(), discountShare.toPlainString(), tax.toPlainString(),
                    l.isTaxExempt(),
                    l.getTaxRate() == null ? null : l.getTaxRate().stripTrailingZeros().toPlainString());
        }
    }

    /**
     * An opened invoice: its lines, subtotal, discount percentage (without trailing zeros, "12.5"),
     * the amount it takes off, the tax rate (without trailing zeros) and the tax charged, the sum of the
     * line taxes, and the grand total; the payments made to it, what they add up to, what is still due and what was written off because less than
     * $1.00 was left (money as
     * decimal strings to the cent). Its status follows what has been paid. {@code reference} is its
     * RF payment reference in groups of four. {@code paidDate} is the date of the payment that cleared
     * it, payments taken in date order; null while it is not fully paid. {@code credited} is what
     * issued credit notes take off it; {@code creditOf} the id of the invoice a credit note credits
     * (null for an ordinary invoice) and {@code listedTotal} the total as listed, negative for a credit note.
     * {@code retentionPct} (without trailing zeros) is the share of the amount before tax (subtotal less discount) held back as
     * {@code retention}, rounded down to the cent; {@code retentionHeld} is what is still held back (not
     * part of the amount due), {@code retentionReleasedOn} the day it was released (null while held) and
     * {@code retentionDueDate} the day released retention falls due. {@code instalments} is how many
     * monthly instalments it is spread over (null without a plan) at the annual {@code instalmentRate}
     * (without trailing zeros); {@code schedule} lists them and {@code planTotal} is what they add up to
     * (null without a plan), from which what is paid comes off for the amount due. {@code listedDueDate}
     * is the due date as listed: for a plan, its next unpaid instalment's. {@code currency} is the
     * currency its client is billed in, which every amount is in (whole yen for a yen client).
     * {@code earlyDiscount} is the early-payment discount it was settled with (0 when none), and
     * {@code settleAmount} what would settle it today while its early-payment window is open (null otherwise).
     * {@code lateInterest} is the late interest charged on it so far, and {@code lateFee} the late fee
     * charged on it (0 when none), both part of the amount due; {@code lateWaived} is the late fee and
     * interest written off by waiving them (0 when none was). {@code payoff} is what would pay off its
     * plan today (null without a plan or once it is paid off), and {@code interestSaved} the interest
     * of instalments not yet due that paying it off early dropped (0 when none was).
     */
    public record InvoiceDetail(Long id, String number, String reference, Long clientId, InvoiceStatus status, LocalDate issueDate,
            LocalDate dueDate, List<LineView> lines, String subtotal, String discountPct, String discountAmount,
            String discountCap, String discount, String discountReason, String taxRate, String tax, String total, List<PaymentView> payments, String paid, String amountDue, String writtenOff, LocalDate paidDate, boolean manualDueDate,
            String credited, Long creditOf, String listedTotal, String retentionPct, String retention,
            String retentionHeld, LocalDate retentionReleasedOn, LocalDate retentionDueDate, Integer instalments,
            String instalmentRate, List<InstalmentView> schedule, String planTotal, LocalDate listedDueDate,
            Currency currency, String earlyDiscount, String settleAmount, String lateInterest, String lateFee, String lateWaived,
            String payoff, String interestSaved) {
        static InvoiceDetail of(Invoice i, BigDecimal taxRate, List<Payment> payments, LocalDate today) {
            BigDecimal paid = payments.stream().map(p -> p.amountTo(i.getId()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
            LocalDate paidOn = i.paidOn(payments, taxRate);
            List<BigDecimal> shares = i.discountShares();
            List<BigDecimal> taxes = i.lineTaxes(taxRate);
            List<LineView> lines = IntStream.range(0, shares.size())
                    .mapToObj(n -> LineView.of(i.getLines().get(n), i.scale(), shares.get(n), taxes.get(n))).toList();
            return new InvoiceDetail(i.getId(), i.number(),
                    PaymentReference.grouped(PaymentReference.of(i.getId())), i.getClientId(),
                    i.statusWhenPaid(paid, paidOn, taxRate, today), i.getIssueDate(), i.getDueDate(),
                    lines, i.subtotal().toPlainString(),
                    i.getDiscountPct().stripTrailingZeros().toPlainString(),
                    i.getDiscountAmount().setScale(2).toPlainString(),
                    i.getDiscountCap() == null ? null : i.getDiscountCap().setScale(2).toPlainString(),
                    i.discount().toPlainString(), i.getDiscountReason(),
                    taxRate.stripTrailingZeros().toPlainString(), i.tax(taxRate).toPlainString(),
                    i.total(taxRate).toPlainString(),
                    payments.stream().map(p -> PaymentView.of(p, i.getId(), i.currency())).toList(), paid.toPlainString(),
                    i.amountDue(paid, taxRate).toPlainString(), i.writtenOff(paid, taxRate).toPlainString(), paidOn,
                    i.isManualDueDate(), i.getCredited().toPlainString(), i.getCreditOf(), InvoiceController.listedTotal(i, taxRate),
                    i.getRetentionPct().stripTrailingZeros().toPlainString(), i.retention(taxRate).toPlainString(),
                    i.retentionHeld(taxRate).toPlainString(), i.getRetentionReleasedOn(), i.retentionDueDate(),
                    i.getInstalments(), i.getInstalmentRate().stripTrailingZeros().toPlainString(),
                    i.instalmentProgress(paid, taxRate, today).stream().map(InstalmentView::of).toList(),
                    i.hasPlan() ? i.planTotal(taxRate).toPlainString() : null, i.listedDueDate(paid, taxRate),
                    i.currency(), i.getEarlyDiscountTaken().toPlainString(),
                    Optional.ofNullable(i.settleAmount(paid, taxRate, today)).map(BigDecimal::toPlainString).orElse(null),
                    i.getLateInterest().toPlainString(), i.getLateFee().toPlainString(),
                    i.getLateWaived().toPlainString(),
                    Optional.ofNullable(i.payoff(paid, taxRate, today)).map(BigDecimal::toPlainString).orElse(null),
                    i.getInterestSaved().toPlainString());
        }
    }

    /**
     * A discount to give: a percentage from 0 to 100 with at most two decimals, or a fixed amount
     * of money, not negative and to the cent. Exactly one of them. Optionally a cap, money not
     * negative and to the cent, that everything taken off, line discounts included, never comes to more than.
     * Optionally a reason, needed when the discount takes off more than a fifth of the subtotal.
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
     * A line to add: a description, a quantity above zero and a unit price of at least $0.01, and
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
            @NotNull(message = "Enter a unit price.")
            @DecimalMin(value = "0.01", message = "The unit price must be at least $0.01.")
            @Digits(integer = 17, fraction = 2, message = "The unit price can have at most two decimals.")
            BigDecimal unitPrice,
            Boolean taxExempt,
            @DecimalMin(value = "0", message = "The line discount must be from 0 to 100 percent.")
            @DecimalMax(value = "100", message = "The line discount must be from 0 to 100 percent.")
            @Digits(integer = 3, fraction = 2, message = "The line discount can have at most two decimals.")
            BigDecimal discountPct,
            @DecimalMin(value = "0", message = "The line tax rate must be from 0 to 100 percent.")
            @DecimalMax(value = "100", message = "The line tax rate must be from 0 to 100 percent.")
            @Digits(integer = 3, fraction = 2, message = "The line tax rate can have at most two decimals.")
            BigDecimal taxRate) {
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
            String reference) {
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
            String reference) {
    }

    /**
     * A refund of unused credit: an amount more than zero with at most two decimals, the currency it is
     * refunded in, and the date it was refunded.
     */
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

    /** A retention to hold back on a draft: a percentage from 0 to 20 with at most two decimals. */
    public record NewRetention(
            @NotNull(message = "Enter a retention percentage.")
            @DecimalMin(value = "0", message = "The retention must be from 0 to 20 percent.")
            @DecimalMax(value = "20", message = "The retention must be from 0 to 20 percent.")
            @Digits(integer = 2, fraction = 2, message = "The retention can have at most two decimals.")
            BigDecimal retentionPct) {
    }

    /**
     * A plan to spread a draft over: a whole number of monthly instalments from 2 to 24 and an annual
     * interest rate from 0 to 30 with at most two decimals.
     */
    public record NewPlan(
            @NotNull(message = "Enter the number of instalments.")
            @DecimalMin(value = "2", message = "The plan must have from 2 to 24 instalments.")
            @DecimalMax(value = "24", message = "The plan must have from 2 to 24 instalments.")
            @Digits(integer = 2, fraction = 0, message = "The number of instalments must be a whole number.")
            BigDecimal instalments,
            @NotNull(message = "Enter the annual interest rate.")
            @DecimalMin(value = "0", message = "The interest rate must be from 0 to 30 percent.")
            @DecimalMax(value = "30", message = "The interest rate must be from 0 to 30 percent.")
            @Digits(integer = 2, fraction = 2, message = "The interest rate can have at most two decimals.")
            BigDecimal instalmentRate) {
    }

    /**
     * One instalment of a plan: its number, the day it falls due, and its payment, interest, principal
     * and the balance left after it, what has been paid off it and the late interest it has been
     * charged, decimal strings to the cent; and its status.
     */
    public record InstalmentView(int number, LocalDate dueDate, String payment, String interest, String principal,
            String balance, String paid, String lateInterest, Instalment.Status status) {
        static InstalmentView of(Instalment.Progress p) {
            Instalment i = p.instalment();
            return new InstalmentView(i.number(), i.dueDate(), i.payment().toPlainString(),
                    i.interest().toPlainString(), i.principal().toPlainString(), i.balance().toPlainString(),
                    p.paid().toPlainString(), p.lateInterest().toPlainString(), p.status());
        }
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
            String reference) {
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
        return InvoiceDetail.of(i, taxRate.percent(), invoices.payments(i.getId()), today.date());
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
     * The balance of each client that has a sent invoice or credit; a client not listed owes nothing and
     * has no credit. Void invoices and credit notes do not count (a credit note comes off its original).
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
                    k -> new BigDecimal[] { BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2) });
            sums[0] = sums[0].add(due).add(i.retentionHeld(rate));
            sums[1] = sums[1].add(i.overdueAmount(paid.get(i.getId()), rate, on));
        }
        Map<Long, BigDecimal> credits = invoices.credits();
        credits.keySet().forEach(clientId -> owed.computeIfAbsent(clientId,
                k -> new BigDecimal[] { BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2) }));
        return owed.entrySet().stream().map(e -> {
            BigDecimal credit = credits.getOrDefault(e.getKey(), BigDecimal.ZERO).setScale(2);
            return new ClientBalance(e.getKey(), e.getValue()[0].subtract(credit).toPlainString(),
                    e.getValue()[1].toPlainString(), credit.toPlainString());
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

    @PostMapping("/api/invoices/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetail addLine(@PathVariable Long id, @Valid @RequestBody NewLine body) {
        return invoices.addLine(id, body.description().trim(), body.qty(), body.unitPrice(),
                Boolean.TRUE.equals(body.taxExempt()),
                body.discountPct() == null ? BigDecimal.ZERO : body.discountPct(), body.taxRate())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    @PutMapping("/api/invoices/{id}/lines/{lineId}")
    public InvoiceDetail updateLine(@PathVariable Long id, @PathVariable Long lineId,
            @Valid @RequestBody NewLine body) {
        return invoices.updateLine(id, lineId, body.description().trim(), body.qty(), body.unitPrice(),
                Boolean.TRUE.equals(body.taxExempt()),
                body.discountPct() == null ? BigDecimal.ZERO : body.discountPct(), body.taxRate())
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

    @PutMapping("/api/invoices/{id}/retention")
    public InvoiceDetail setRetention(@PathVariable Long id, @Valid @RequestBody NewRetention body) {
        return invoices.setRetention(id, body.retentionPct())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    @PutMapping("/api/invoices/{id}/plan")
    public InvoiceDetail setPlan(@PathVariable Long id, @Valid @RequestBody NewPlan body) {
        return invoices.setPlan(id, body.instalments().intValueExact(), body.instalmentRate())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Releases the retention held back on a sent invoice once nothing else is due. */
    @PostMapping("/api/invoices/{id}/release-retention")
    public InvoiceDetail releaseRetention(@PathVariable Long id) {
        return invoices.releaseRetention(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    /** Waives the late fee and late interest still unpaid on an overdue invoice. */
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

    @PostMapping("/api/invoices/{id}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetail duplicate(@PathVariable Long id) {
        return invoices.duplicate(id)
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
     * to all its unpaid invoices, overdue first (earliest due date first), then the largest amount due first; answers the client's invoices
     * with their new statuses.
     */
    @PostMapping("/api/clients/{clientId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public List<InvoiceSummary> recordSplitPayment(@PathVariable Long clientId, @Valid @RequestBody NewSplitPayment body) {
        invoices.recordAutoPayment(clientId, body.amount(), body.currency(), body.date(), references(body.reference()));
        return forClient(clientId);
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
        invoices.recordDeposit(clientId, body.amount(), body.currency(), body.date(), reference(body.reference()))
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
        return invoices.recordPayment(id, body.amount(), body.currency(), body.date(), reference(body.reference()))
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
