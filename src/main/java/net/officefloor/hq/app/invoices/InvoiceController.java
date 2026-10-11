package net.officefloor.hq.app.invoices;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

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

    private final InvoiceService invoices;

    public InvoiceController(InvoiceService invoices) {
        this.invoices = invoices;
    }

    @GetMapping("/api/clients/{clientId}/invoices")
    public List<InvoiceSummary> forClient(@PathVariable Long clientId) {
        return invoices.forClient(clientId).stream().map(InvoiceSummary::of).toList();
    }
}
