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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.IntStream;
import net.officefloor.hq.app.TaxRate;
import net.officefloor.hq.app.Today;
import org.springframework.http.HttpStatus;
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
            LocalDate issueDate, LocalDate dueDate) {
        static InvoiceSummary of(Invoice i, BigDecimal taxRate, BigDecimal paid, LocalDate today) {
            BigDecimal total = i.total(taxRate);
            return new InvoiceSummary(i.getId(), InvoiceNumber.of(i.getId()), i.statusWhenPaid(paid, taxRate, today),
                    total.toPlainString(), i.getIssueDate(), i.getDueDate());
        }
    }

    /**
     * What a client still owes across its sent invoices (drafts do not count), after discounts, tax
     * and payments, and how much of that is overdue; decimal strings to the cent.
     */
    public record ClientBalance(Long clientId, String balance, String overdue) {
    }

    /**
     * A payment made to an opened invoice: the whole amount paid and how much of it went to this
     * invoice (to the cent), its date and the reference it quoted in groups of four (null when none).
     */
    public record PaymentView(Long id, String amount, String allocated, LocalDate date, String reference) {
        static PaymentView of(Payment p, Long invoiceId) {
            return new PaymentView(p.getId(), p.getAmount().setScale(2).toPlainString(),
                    p.amountTo(invoiceId).setScale(2).toPlainString(), p.getDate(),
                    p.getReference() == null ? null : PaymentReference.grouped(p.getReference()));
        }
    }

    /**
     * One line of an opened invoice. The quantity is as entered, without trailing zeros ("1.5");
     * money is a decimal string to the cent. {@code discountShare} is the line's share of the
     * invoice discount; {@code tax} is the line's tax, the rate on its amount less that share, and
     * {@code taxExempt} lines have none. {@code discountPct}
     * (without trailing zeros) is the line's own discount and {@code discount} what it takes off;
     * {@code amount} is after it.
     */
    public record LineView(Long id, String description, String qty, String unitPrice, String discountPct,
            String discount, String amount, String discountShare, String tax, boolean taxExempt) {
        static LineView of(InvoiceLine l, BigDecimal discountShare, BigDecimal tax) {
            return new LineView(l.getId(), l.getDescription(), l.getQty().stripTrailingZeros().toPlainString(),
                    l.getUnitPrice().setScale(2).toPlainString(),
                    l.getDiscountPct().stripTrailingZeros().toPlainString(), l.discount().toPlainString(),
                    l.amount().toPlainString(), discountShare.toPlainString(), tax.toPlainString(),
                    l.isTaxExempt());
        }
    }

    /**
     * An opened invoice: its lines, subtotal, discount percentage (without trailing zeros, "12.5"),
     * the amount it takes off, the tax rate (without trailing zeros) and the tax charged, the sum of the
     * line taxes, and the grand total; the payments made to it, what they add up to and what is still due (money as
     * decimal strings to the cent). Its status follows what has been paid. {@code reference} is its
     * RF payment reference in groups of four.
     */
    public record InvoiceDetail(Long id, String number, String reference, Long clientId, InvoiceStatus status, LocalDate issueDate,
            LocalDate dueDate, List<LineView> lines, String subtotal, String discountPct, String discountAmount,
            String discountCap, String discount, String taxRate, String tax, String total, List<PaymentView> payments, String paid, String amountDue) {
        static InvoiceDetail of(Invoice i, BigDecimal taxRate, List<Payment> payments, LocalDate today) {
            BigDecimal paid = payments.stream().map(p -> p.amountTo(i.getId()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
            List<BigDecimal> shares = i.discountShares();
            List<BigDecimal> taxes = i.lineTaxes(taxRate);
            List<LineView> lines = IntStream.range(0, shares.size())
                    .mapToObj(n -> LineView.of(i.getLines().get(n), shares.get(n), taxes.get(n))).toList();
            return new InvoiceDetail(i.getId(), InvoiceNumber.of(i.getId()),
                    PaymentReference.grouped(PaymentReference.of(i.getId())), i.getClientId(),
                    i.statusWhenPaid(paid, taxRate, today), i.getIssueDate(), i.getDueDate(),
                    lines, i.subtotal().toPlainString(),
                    i.getDiscountPct().stripTrailingZeros().toPlainString(),
                    i.getDiscountAmount().setScale(2).toPlainString(),
                    i.getDiscountCap() == null ? null : i.getDiscountCap().setScale(2).toPlainString(),
                    i.discount().toPlainString(),
                    taxRate.stripTrailingZeros().toPlainString(), i.tax(taxRate).toPlainString(),
                    i.total(taxRate).toPlainString(),
                    payments.stream().map(p -> PaymentView.of(p, i.getId())).toList(), paid.toPlainString(),
                    i.amountDue(paid, taxRate).toPlainString());
        }
    }

    /**
     * A discount to give: a percentage from 0 to 100 with at most two decimals, or a fixed amount
     * of money, not negative and to the cent. Exactly one of them. Optionally a cap, money not
     * negative and to the cent, that a percentage never takes off more than.
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
            BigDecimal discountCap) {

        @AssertTrue(message = "Enter either a discount percentage or a discount amount.")
        public boolean isDiscountGiven() {
            return (discountPct == null) != (discountAmount == null);
        }
    }

    /**
     * A line to add: a description, a quantity above zero and a unit price of at least $0.01, and
     * whether it is tax exempt (taxable when left out), and its own discount percentage from 0 to 100
     * with at most two decimals (none when left out).
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
            BigDecimal discountPct) {
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
            @NotNull(message = "Enter the date it was paid.")
            LocalDate date,
            @Size(max = 64, message = "The reference must be at most 64 characters.")
            String reference) {
    }

    /** The part of a split payment that goes to one invoice: more than zero, with at most two decimals. */
    public record NewAllocation(
            @NotNull(message = "Choose the invoice each part of the payment goes to.")
            Long invoiceId,
            @NotNull(message = "Enter how much of the payment goes to each invoice.")
            @DecimalMin(value = "0", inclusive = false, message = "Each invoice's part must be more than zero.")
            @Digits(integer = 17, fraction = 2, message = "Each invoice's part can have at most two decimals.")
            BigDecimal amount) {
    }

    /** A payment to spread over several of a client's invoices: a payment and the part going to each. */
    public record NewSplitPayment(
            @NotNull(message = "Enter an amount.")
            @DecimalMin(value = "0", inclusive = false, message = "The amount must be more than zero.")
            @Digits(integer = 17, fraction = 2, message = "The amount can have at most two decimals.")
            BigDecimal amount,
            @NotNull(message = "Enter the date it was paid.")
            LocalDate date,
            @Size(max = 64, message = "The reference must be at most 64 characters.")
            String reference,
            @NotNull(message = "Enter how much of the payment goes to each invoice.")
            List<@Valid NewAllocation> allocations) {
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
        return InvoiceSummary.of(i, taxRate.percent(), invoices.paid(List.of(i)).get(i.getId()), today.date());
    }

    private InvoiceDetail detail(Invoice i) {
        return InvoiceDetail.of(i, taxRate.percent(), invoices.payments(i.getId()), today.date());
    }

    @GetMapping("/api/clients/{clientId}/invoices")
    public List<InvoiceSummary> forClient(@PathVariable Long clientId) {
        BigDecimal rate = taxRate.percent();
        List<Invoice> list = invoices.forClient(clientId);
        Map<Long, BigDecimal> paid = invoices.paid(list);
        LocalDate on = today.date();
        return list.stream().map(i -> InvoiceSummary.of(i, rate, paid.get(i.getId()), on)).toList();
    }

    /** The balance of each client that has a sent invoice; a client not listed owes nothing. Void invoices do not count. */
    @GetMapping("/api/clients/balances")
    public List<ClientBalance> balances() {
        BigDecimal rate = taxRate.percent();
        List<Invoice> sent = invoices.all().stream().filter(i -> !i.isDraft() && !i.isVoid()).toList();
        Map<Long, BigDecimal> paid = invoices.paid(sent);
        LocalDate on = today.date();
        Map<Long, BigDecimal[]> owed = new TreeMap<>();
        for (Invoice i : sent) {
            BigDecimal due = i.amountDue(paid.get(i.getId()), rate);
            BigDecimal[] sums = owed.computeIfAbsent(i.getClientId(),
                    k -> new BigDecimal[] { BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2) });
            sums[0] = sums[0].add(due);
            if (i.statusWhenPaid(paid.get(i.getId()), rate, on) == InvoiceStatus.OVERDUE) {
                sums[1] = sums[1].add(due);
            }
        }
        return owed.entrySet().stream().map(e -> new ClientBalance(e.getKey(),
                e.getValue()[0].toPlainString(), e.getValue()[1].toPlainString())).toList();
    }

    @PostMapping("/api/clients/{clientId}/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceSummary newDraft(@PathVariable Long clientId) {
        return invoices.newDraft(clientId).map(this::summary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such client."));
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
        return invoices.find(id).map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "There is no invoice " + number.trim() + "."));
    }

    @PostMapping("/api/invoices/{id}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetail addLine(@PathVariable Long id, @Valid @RequestBody NewLine body) {
        return invoices.addLine(id, body.description().trim(), body.qty(), body.unitPrice(),
                Boolean.TRUE.equals(body.taxExempt()),
                body.discountPct() == null ? BigDecimal.ZERO : body.discountPct())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    @PutMapping("/api/invoices/{id}/discount")
    public InvoiceDetail setDiscount(@PathVariable Long id, @Valid @RequestBody NewDiscount body) {
        return invoices.setDiscount(id, body.discountPct(), body.discountAmount(), body.discountCap())
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
     * Records one payment spread over several of the client's invoices, or, with no parts given,
     * applied to its oldest unpaid invoices first; answers the client's invoices
     * with their new statuses.
     */
    @PostMapping("/api/clients/{clientId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public List<InvoiceSummary> recordSplitPayment(@PathVariable Long clientId, @Valid @RequestBody NewSplitPayment body) {
        if (body.allocations().isEmpty()) {
            invoices.recordAutoPayment(clientId, body.amount(), body.date(), reference(body.reference()));
        } else {
            invoices.recordSplitPayment(clientId, body.amount(), body.date(), reference(body.reference()),
                    body.allocations().stream().map(a -> new PaymentAllocation(a.invoiceId(), a.amount())).toList());
        }
        return forClient(clientId);
    }

    /** The quoted reference without spaces, or null when none was; a mistyped one is refused. */
    private static String reference(String typed) {
        if (typed == null || typed.isBlank()) {
            return null;
        }
        return PaymentReference.parse(typed).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, typed.trim() + " is mistyped: check the payment reference."));
    }

    @PostMapping("/api/invoices/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetail recordPayment(@PathVariable Long id, @Valid @RequestBody NewPayment body) {
        return invoices.recordPayment(id, body.amount(), body.date(), reference(body.reference()))
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }
}
