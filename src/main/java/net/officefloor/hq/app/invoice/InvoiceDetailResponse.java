package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.client.Currency;

/**
 * A single invoice together with the line items it is built from: their subtotal, each discount on it
 * (with what it takes off), the percentage discounts and flat amount discounts added up, and what they all take off together, the sales tax percentage, the taxable base it is charged on (the
 * taxable lines after the discount) and what it adds, the levy (second tax) percentage and what it adds
 * on the same base, any flat surcharge (such as a handling fee) added after tax, the amount owed including both taxes and the surcharge, the total before tax, whether the prices already include the taxes
 * (so they are worked back out rather than added on), whether the client is tax exempt (so
 * there is no tax at all), the effective tax rate (the sales tax and levy together as a percentage of
 * the total before tax), any early-payment discount offered (its percentage, the days after issue it
 * must be paid within, the last day to pay, and the reduced amount; null when none is offered), and the
 * client's tax number (null when the client is not tax registered).
 */
public record InvoiceDetailResponse(Long id, Long projectId, Currency currency, BigDecimal amount, BigDecimal totalExTax,
        BigDecimal subtotal,
        BigDecimal discountPct, BigDecimal discountAmount, BigDecimal discount, BigDecimal taxPct, BigDecimal taxableBase, BigDecimal tax,
        BigDecimal levyPct, BigDecimal levy, BigDecimal surcharge, boolean taxInclusive, boolean taxExempt, BigDecimal effectiveTaxPct,
        BigDecimal earlyPaymentPct, int earlyPaymentDays, LocalDate earlyPaymentBy, BigDecimal earlyPaymentAmount,
        InvoiceStatus status, LocalDate issuedDate,
        LocalDate dueDate, List<LineItemResponse> lineItems, String clientTaxNumber, List<InvoiceDiscountResponse> discounts) {

    static InvoiceDetailResponse from(Invoice invoice) {
        return new InvoiceDetailResponse(invoice.getId(), invoice.getProject().getId(),
                invoice.getProject().getClient().getCurrency(), invoice.getAmount(), invoice.getTotalExTax(),
                invoice.getSubtotal(), invoice.getDiscountPct(), invoice.getDiscountAmount(), invoice.getDiscount(),
                invoice.getTaxPct(), invoice.getTaxableBase(), invoice.getTax(),
                invoice.getLevyPct(), invoice.getLevy(), invoice.getSurcharge(), invoice.isTaxInclusive(), invoice.isTaxExempt(),
                invoice.getEffectiveTaxPct(),
                invoice.getEarlyPaymentPct(), invoice.getEarlyPaymentDays(), invoice.getEarlyPaymentBy(),
                invoice.getEarlyPaymentAmount(),
                invoice.getStatus(), invoice.getIssuedDate(), invoice.getDueDate(),
                invoice.getLineItems().stream().map(LineItemResponse::from).toList(),
                invoice.getProject().getClient().getTaxNumber(),
                invoice.getDiscounts().stream().map(d -> new InvoiceDiscountResponse(d.getId(), d.getDiscountPct(),
                        d.getDiscountAmount(), d.getDiscountCap(), invoice.discountTakenBy(d))).toList());
    }
}
