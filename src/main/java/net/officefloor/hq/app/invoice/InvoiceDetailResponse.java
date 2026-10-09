package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A single invoice together with the line items it is built from: their subtotal, the percentage
 * discount and what it takes off, and the amount owed after it.
 */
public record InvoiceDetailResponse(Long id, Long projectId, BigDecimal amount, BigDecimal subtotal,
        BigDecimal discountPct, BigDecimal discount, InvoiceStatus status, LocalDate issuedDate,
        LocalDate dueDate, List<LineItemResponse> lineItems) {

    static InvoiceDetailResponse from(Invoice invoice) {
        return new InvoiceDetailResponse(invoice.getId(), invoice.getProject().getId(), invoice.getAmount(),
                invoice.getSubtotal(), invoice.getDiscountPct(), invoice.getDiscount(),
                invoice.getStatus(), invoice.getIssuedDate(), invoice.getDueDate(),
                invoice.getLineItems().stream().map(LineItemResponse::from).toList());
    }
}
