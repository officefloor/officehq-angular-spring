package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A single invoice together with the line items it is built from. */
public record InvoiceDetailResponse(Long id, Long projectId, BigDecimal amount, InvoiceStatus status,
        LocalDate issuedDate, LocalDate dueDate, List<LineItemResponse> lineItems) {

    static InvoiceDetailResponse from(Invoice invoice) {
        return new InvoiceDetailResponse(invoice.getId(), invoice.getProject().getId(), invoice.getAmount(),
                invoice.getStatus(), invoice.getIssuedDate(), invoice.getDueDate(),
                invoice.getLineItems().stream().map(LineItemResponse::from).toList());
    }
}
