package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.client.Currency;

/**
 * A single invoice together with the line items it is built from: their subtotal, the percentage
 * discount and what it takes off, the sales tax percentage, the taxable base it is charged on (the
 * taxable lines after the discount) and what it adds, the levy (second tax) percentage and what it adds
 * on the same base, the amount owed including both taxes, the total before tax, whether the prices already include the taxes
 * (so they are worked back out rather than added on), whether the client is tax exempt (so
 * there is no tax at all), and the
 * client's tax number (null when the client is not tax registered).
 */
public record InvoiceDetailResponse(Long id, Long projectId, Currency currency, BigDecimal amount, BigDecimal totalExTax,
        BigDecimal subtotal,
        BigDecimal discountPct, BigDecimal discount, BigDecimal taxPct, BigDecimal taxableBase, BigDecimal tax,
        BigDecimal levyPct, BigDecimal levy, boolean taxInclusive, boolean taxExempt, InvoiceStatus status, LocalDate issuedDate,
        LocalDate dueDate, List<LineItemResponse> lineItems, String clientTaxNumber) {

    static InvoiceDetailResponse from(Invoice invoice) {
        return new InvoiceDetailResponse(invoice.getId(), invoice.getProject().getId(),
                invoice.getProject().getClient().getCurrency(), invoice.getAmount(), invoice.getTotalExTax(),
                invoice.getSubtotal(), invoice.getDiscountPct(), invoice.getDiscount(),
                invoice.getTaxPct(), invoice.getTaxableBase(), invoice.getTax(),
                invoice.getLevyPct(), invoice.getLevy(), invoice.isTaxInclusive(), invoice.isTaxExempt(),
                invoice.getStatus(), invoice.getIssuedDate(), invoice.getDueDate(),
                invoice.getLineItems().stream().map(LineItemResponse::from).toList(),
                invoice.getProject().getClient().getTaxNumber());
    }
}
