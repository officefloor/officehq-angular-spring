package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;

/** What a client owed as at the end of a chosen day, in the client's currency (negative when in credit). */
public record ClientBalanceAsOfResponse(Long clientId, String currency, LocalDate asOf, BigDecimal balance) {
}
