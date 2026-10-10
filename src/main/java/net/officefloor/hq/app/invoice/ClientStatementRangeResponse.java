package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A client's statement for a chosen date range, in the client's currency: the balance owed at the start of the range
 * (every entry dated before it), the entries dated within it each with the running balance once it is counted, and the
 * closing balance owed at the end of the range (the opening balance plus what changed within it).
 */
public record ClientStatementRangeResponse(Long clientId, String currency, LocalDate from, LocalDate to,
        BigDecimal openingBalance, List<StatementEntry> entries, BigDecimal closingBalance) {
}
