package net.officefloor.hq.app.invoices;

import jakarta.validation.Valid;
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
import java.util.stream.IntStream;
import net.officefloor.hq.app.TaxRate;
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
     * follows what has been paid.
     */
    public record InvoiceSummary(Long id, String number, InvoiceStatus status, String total,
            LocalDate issueDate, LocalDate dueDate) {
        static InvoiceSummary of(Invoice i, BigDecimal taxRate, BigDecimal paid) {
            BigDecimal total = i.total(taxRate);
            return new InvoiceSummary(i.getId(), InvoiceNumber.of(i.getId()), i.statusWhenPaid(paid, taxRate),
                    total.toPlainString(), i.getIssueDate(), i.getDueDate());
        }
    }

    /** A payment made to an opened invoice: the amount that went to it (to the cent) and its date. */
    public record PaymentView(Long id, String amount, LocalDate date) {
        static PaymentView of(Payment p, Long invoiceId) {
            return new PaymentView(p.getId(), p.amountTo(invoiceId).setScale(2).toPlainString(), p.getDate());
        }
    }

    /**
     * One line of an opened invoice. The quantity is as entered, without trailing zeros ("1.5");
     * money is a decimal string to the cent. {@code discountShare} is the line's share of the
     * invoice discount; {@code taxExempt} lines are left out of the taxed amount.
     */
    public record LineView(Long id, String description, String qty, String unitPrice, String amount,
            String discountShare, boolean taxExempt) {
        static LineView of(InvoiceLine l, BigDecimal discountShare) {
            return new LineView(l.getId(), l.getDescription(), l.getQty().stripTrailingZeros().toPlainString(),
                    l.getUnitPrice().setScale(2).toPlainString(), l.amount().toPlainString(),
                    discountShare.toPlainString(), l.isTaxExempt());
        }
    }

    /**
     * An opened invoice: its lines, subtotal, discount percentage (without trailing zeros, "12.5"),
     * the amount it takes off, the tax rate (without trailing zeros) and the tax charged on the taxable
     * lines less their discount shares, and the
     * grand total; the payments made to it, what they add up to and what is still due (money as
     * decimal strings to the cent). Its status follows what has been paid.
     */
    public record InvoiceDetail(Long id, String number, Long clientId, InvoiceStatus status, LocalDate issueDate,
            LocalDate dueDate, List<LineView> lines, String subtotal, String discountPct, String discount,
            String taxRate, String tax, String total, List<PaymentView> payments, String paid, String amountDue) {
        static InvoiceDetail of(Invoice i, BigDecimal taxRate, List<Payment> payments) {
            BigDecimal paid = payments.stream().map(p -> p.amountTo(i.getId()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
            List<BigDecimal> shares = i.discountShares();
            List<LineView> lines = IntStream.range(0, shares.size())
                    .mapToObj(n -> LineView.of(i.getLines().get(n), shares.get(n))).toList();
            return new InvoiceDetail(i.getId(), InvoiceNumber.of(i.getId()), i.getClientId(),
                    i.statusWhenPaid(paid, taxRate), i.getIssueDate(), i.getDueDate(),
                    lines, i.subtotal().toPlainString(),
                    i.getDiscountPct().stripTrailingZeros().toPlainString(), i.discount().toPlainString(),
                    taxRate.stripTrailingZeros().toPlainString(), i.tax(taxRate).toPlainString(),
                    i.total(taxRate).toPlainString(),
                    payments.stream().map(p -> PaymentView.of(p, i.getId())).toList(), paid.toPlainString(),
                    i.amountDue(paid, taxRate).toPlainString());
        }
    }

    /** A discount to give: a percentage from 0 to 100 with at most two decimals. */
    public record NewDiscount(
            @NotNull(message = "Enter a discount percentage.")
            @DecimalMin(value = "0", message = "The discount must be from 0 to 100 percent.")
            @DecimalMax(value = "100", message = "The discount must be from 0 to 100 percent.")
            @Digits(integer = 3, fraction = 2, message = "The discount can have at most two decimals.")
            BigDecimal discountPct) {
    }

    /**
     * A line to add: a description, a quantity above zero and a unit price of at least $0.01, and
     * whether it is tax exempt (taxable when left out).
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
            Boolean taxExempt) {
    }

    /** A payment to record: an amount more than zero with at most two decimals, and the date it was paid. */
    public record NewPayment(
            @NotNull(message = "Enter an amount.")
            @DecimalMin(value = "0", inclusive = false, message = "The amount must be more than zero.")
            @Digits(integer = 17, fraction = 2, message = "The amount can have at most two decimals.")
            BigDecimal amount,
            @NotNull(message = "Enter the date it was paid.")
            LocalDate date) {
    }

    private final InvoiceService invoices;
    private final TaxRate taxRate;

    public InvoiceController(InvoiceService invoices, TaxRate taxRate) {
        this.invoices = invoices;
        this.taxRate = taxRate;
    }

    private InvoiceSummary summary(Invoice i) {
        return InvoiceSummary.of(i, taxRate.percent(), invoices.paid(List.of(i)).get(i.getId()));
    }

    private InvoiceDetail detail(Invoice i) {
        return InvoiceDetail.of(i, taxRate.percent(), invoices.payments(i.getId()));
    }

    @GetMapping("/api/clients/{clientId}/invoices")
    public List<InvoiceSummary> forClient(@PathVariable Long clientId) {
        BigDecimal rate = taxRate.percent();
        List<Invoice> list = invoices.forClient(clientId);
        Map<Long, BigDecimal> paid = invoices.paid(list);
        return list.stream().map(i -> InvoiceSummary.of(i, rate, paid.get(i.getId()))).toList();
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
                Boolean.TRUE.equals(body.taxExempt()))
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    @PutMapping("/api/invoices/{id}/discount")
    public InvoiceDetail setDiscount(@PathVariable Long id, @Valid @RequestBody NewDiscount body) {
        return invoices.setDiscount(id, body.discountPct())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    @PostMapping("/api/invoices/{id}/send")
    public InvoiceDetail send(@PathVariable Long id) {
        return invoices.send(id)
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }

    @PostMapping("/api/invoices/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceDetail recordPayment(@PathVariable Long id, @Valid @RequestBody NewPayment body) {
        return invoices.recordPayment(id, body.amount(), body.date())
                .map(this::detail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }
}
