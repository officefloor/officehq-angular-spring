package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A client's statement for a chosen date range, in the client's currency: the balance owed at the start of the range
 * (every entry dated before it), the entries dated within it each with the running balance once it is counted, and the
 * closing balance owed at the end of the range (the opening balance plus what changed within it). The movements total
 * is that change: the charges less the credits dated within the range, so opening plus movements is always closing.
 * The payments received within the range (not those made out of held deposits) are listed again on their own, with
 * their total, and so are the credit notes issued within the range, with theirs.
 */
public record ClientStatementRangeResponse(Long clientId, String currency, LocalDate from, LocalDate to,
        BigDecimal openingBalance, List<StatementEntry> entries, BigDecimal movementsTotal, BigDecimal closingBalance,
        List<StatementEntry> payments, BigDecimal paymentsTotal, List<StatementEntry> credits,
        BigDecimal creditsTotal) {
}
