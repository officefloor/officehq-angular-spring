package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.client.Currency;

/**
 * A single invoice together with the line items it is built from: their subtotal, the percentage
 * discount and what it takes off, the sales tax percentage and what it adds after the discount, and
 * the amount owed including that tax.
 */
public record InvoiceDetailResponse(Long id, Long projectId, Currency currency, BigDecimal amount, BigDecimal subtotal,
        BigDecimal discountPct, BigDecimal discount, BigDecimal taxPct, BigDecimal tax, InvoiceStatus status, LocalDate issuedDate,
        LocalDate dueDate, List<LineItemResponse> lineItems) {

    static InvoiceDetailResponse from(Invoice invoice) {
        return new InvoiceDetailResponse(invoice.getId(), invoice.getProject().getId(),
                invoice.getProject().getClient().getCurrency(), invoice.getAmount(),
                invoice.getSubtotal(), invoice.getDiscountPct(), invoice.getDiscount(),
                invoice.getTaxPct(), invoice.getTax(),
                invoice.getStatus(), invoice.getIssuedDate(), invoice.getDueDate(),
                invoice.getLineItems().stream().map(LineItemResponse::from).toList());
    }
}
