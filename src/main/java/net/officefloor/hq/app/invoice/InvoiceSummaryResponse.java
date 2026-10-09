package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.client.Currency;

/** An invoice as listed across all projects: carries the name of the project it is for. */
public record InvoiceSummaryResponse(Long id, Long projectId, String projectName, Currency currency, BigDecimal amount,
        InvoiceStatus status, LocalDate issuedDate, LocalDate dueDate) {

    static InvoiceSummaryResponse from(Invoice invoice) {
        return new InvoiceSummaryResponse(invoice.getId(), invoice.getProject().getId(),
                invoice.getProject().getName(), invoice.getProject().getClient().getCurrency(), invoice.getAmount(), invoice.getStatus(),
                invoice.getIssuedDate(), invoice.getDueDate());
    }
}
