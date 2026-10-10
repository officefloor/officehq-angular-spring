package net.officefloor.hq.app.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A remittance note for a lump payment: what was received ({@code amount}, in the client's {@code currency}), how much
 * of the client's credit it used up and kept as credit, and the invoices it covered. Each line's {@code amount} is the
 * share of the lump put toward that invoice, in the client's currency; {@code settled} is what that share settled on
 * the invoice, in the invoice's currency.
 */
public record RemittanceResponse(Long id, Long clientId, String currency, BigDecimal amount, LocalDate date,
        BigDecimal fromDeposits, BigDecimal fromCreditNotes, BigDecimal toCredit, List<Line> lines) {

    /** One invoice the lump payment covered. */
    public record Line(Long invoiceId, Long projectId, String projectName, BigDecimal amount, BigDecimal settled,
            String invoiceCurrency) {
    }
}
