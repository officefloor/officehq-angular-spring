package net.officefloor.hq.app.invoices;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class InvoiceController {

    /** An invoice as listed: its total is a decimal string to the cent, e.g. "25.25". */
    public record InvoiceSummary(Long id, InvoiceStatus status, String total, LocalDate issueDate,
            LocalDate dueDate) {
        static InvoiceSummary of(Invoice i) {
            BigDecimal total = i.total();
            return new InvoiceSummary(i.getId(), i.getStatus(), total.toPlainString(), i.getIssueDate(),
                    i.getDueDate());
        }
    }

    /**
     * One line of an opened invoice. The quantity is as entered, without trailing zeros ("1.5");
     * money is a decimal string to the cent.
     */
    public record LineView(Long id, String description, String qty, String unitPrice, String amount) {
        static LineView of(InvoiceLine l) {
            return new LineView(l.getId(), l.getDescription(), l.getQty().stripTrailingZeros().toPlainString(),
                    l.getUnitPrice().setScale(2).toPlainString(), l.amount().toPlainString());
        }
    }

    /** An opened invoice: its lines, subtotal and grand total (money as decimal strings to the cent). */
    public record InvoiceDetail(Long id, Long clientId, InvoiceStatus status, LocalDate issueDate,
            LocalDate dueDate, List<LineView> lines, String subtotal, String total) {
        static InvoiceDetail of(Invoice i) {
            String total = i.total().toPlainString();
            return new InvoiceDetail(i.getId(), i.getClientId(), i.getStatus(), i.getIssueDate(), i.getDueDate(),
                    i.getLines().stream().map(LineView::of).toList(), total, total);
        }
    }

    private final InvoiceService invoices;

    public InvoiceController(InvoiceService invoices) {
        this.invoices = invoices;
    }

    @GetMapping("/api/clients/{clientId}/invoices")
    public List<InvoiceSummary> forClient(@PathVariable Long clientId) {
        return invoices.forClient(clientId).stream().map(InvoiceSummary::of).toList();
    }

    @PostMapping("/api/clients/{clientId}/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceSummary newDraft(@PathVariable Long clientId) {
        return invoices.newDraft(clientId).map(InvoiceSummary::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such client."));
    }

    @GetMapping("/api/invoices/{id}")
    public InvoiceDetail open(@PathVariable Long id) {
        return invoices.find(id).map(InvoiceDetail::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such invoice."));
    }
}
