package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A single invoice together with the line items it is built from: their subtotal, its discount
 * (at most one, with what it takes off), its percentage or flat amount, and what it takes off, the sales tax percentage, the taxable base it is charged on (the
 * taxable lines after the discount) and what it adds, any flat surcharge (such as a handling fee) added after tax, the amount owed including the tax and the surcharge, the total before tax, whether the prices already include the tax
 * (so it is worked back out rather than added on), whether the client is tax exempt (so
 * there is no tax at all), the effective tax rate (the sales tax as a percentage of
 * the total before tax), any early-payment discount offered (its percentage, the days after issue it
 * must be paid within, the last day to pay, and the reduced amount; null when none is offered), any
 * settlement rebate for paying before the due date (its percentage, and the rebate itself; null when none
 * is offered, and the reduced amount the client actually needed to pay once the rebate was earned; null until
 * then), and the
 * client's tax number (null when the client is not tax registered), the minimum charge, the net total
 * before it, whether the minimum was billed because the net total came out under it, and the total
 * savings (the line discounts and the invoice's discounts added together). A foreign invoice also gives
 * the home currency and its amount converted into it at the exchange rate from the invoice's issue date
 * (null when the invoice is already in the home currency or there is no rate for that date). The late
 * fee charged per day overdue, the days the invoice is overdue as of today (only while sent and still
 * owed), and the late fee that has accrued over them. The percentage held back as retention, the amount
 * it holds back (not due yet, nothing once released), whether it has been released, and what is due now
 * without it. The part written off as bad debt. What the client owes right now after the payments and credit notes against it, less any
 * part written off and any retention still held back. The exchange gain (positive) or loss (negative) realised
 * in the home currency by payments on a foreign invoice, from the rate moving between the invoice's issue date
 * and each payment's date (null when the invoice is in the home currency, has no payments, or a rate is missing).
 * The client's purchase-order number the invoice is raised against (null when none was given).
 * The invoice as it read when it was sent, unaffected by later changes (null until it is sent).
 */
public record InvoiceDetailResponse(Long id, Long projectId, String currency, BigDecimal amount, BigDecimal totalExTax,
        BigDecimal subtotal,
        BigDecimal discountPct, BigDecimal discountAmount, BigDecimal discount, BigDecimal taxPct, BigDecimal taxableBase, BigDecimal tax,
        BigDecimal surcharge, boolean taxInclusive, boolean taxExempt, BigDecimal effectiveTaxPct,
        BigDecimal earlyPaymentPct, int earlyPaymentDays, LocalDate earlyPaymentBy, BigDecimal earlyPaymentAmount,
        BigDecimal rebatePct, BigDecimal rebate, BigDecimal rebatedAmount,
        InvoiceStatus status, LocalDate issuedDate,
        LocalDate dueDate, List<LineItemResponse> lineItems, String clientTaxNumber, List<InvoiceDiscountResponse> discounts,
        BigDecimal minimumCharge, BigDecimal netTotal, boolean minimumApplied, BigDecimal totalSavings,
        String homeCurrency, BigDecimal homeAmount,
        BigDecimal lateFeePerDay, long daysLate, BigDecimal lateFee,
        BigDecimal retentionPct, BigDecimal retention, boolean retentionReleased, BigDecimal dueNow, BigDecimal writeOffAmount, BigDecimal amountDue,
        BigDecimal fxGainLoss, String poNumber, InvoiceSnapshot sentSnapshot) {

    /**
     * The invoice with the given status, as worked out from what has been paid and credited against it, and
     * its amount in the home currency, with any late fee accrued by today.
     */
    static InvoiceDetailResponse from(Invoice invoice, InvoiceStatus status, String homeCurrency, BigDecimal homeAmount,
            LocalDate today, BigDecimal amountDue, BigDecimal fxGainLoss) {
        return new InvoiceDetailResponse(invoice.getId(), invoice.getProject().getId(),
                invoice.getCurrency(), invoice.getAmount(), invoice.getTotalExTax(),
                invoice.getSubtotal(), invoice.getDiscountPct(), invoice.getDiscountAmount(), invoice.getDiscount(),
                invoice.getTaxPct(), invoice.getTaxableBase(), invoice.getTax(),
                invoice.getSurcharge(), invoice.isTaxInclusive(), invoice.isTaxExempt(),
                invoice.getEffectiveTaxPct(),
                invoice.getEarlyPaymentPct(), invoice.getEarlyPaymentDays(), invoice.getEarlyPaymentBy(),
                invoice.getEarlyPaymentAmount(),
                invoice.getRebatePct(), invoice.getRebate(), invoice.getRebatedAmount(),
                status, invoice.getIssuedDate(), invoice.getDueDate(),
                invoice.getLineItems().stream().map(LineItemResponse::from).toList(),
                invoice.getProject().getClient().getTaxNumber(),
                invoice.getDiscounts().stream().map(d -> new InvoiceDiscountResponse(d.getId(), d.getDiscountPct(),
                        d.getDiscountAmount(), d.getDiscountCap(), invoice.discountTakenBy(d))).toList(),
                invoice.getMinimumCharge(), invoice.getNetTotal(), invoice.isMinimumApplied(),
                invoice.getTotalSavings(), homeCurrency, homeAmount,
                invoice.getLateFeePerDay(), invoice.daysLate(status, today), invoice.lateFee(status, today),
                invoice.getRetentionPct(), invoice.getRetention(), invoice.isRetentionReleased(), invoice.getDueNow(),
                invoice.getWriteOffAmount(), amountDue, fxGainLoss, invoice.getPoNumber(),
                invoice.getSentSnapshot().orElse(null));
    }
}
