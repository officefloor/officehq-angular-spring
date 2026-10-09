package net.officefloor.hq.app.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import net.officefloor.hq.app.client.Currency;

/** What a client owed as at the end of a chosen day, in the client's currency (negative when in credit). */
public record ClientBalanceAsOfResponse(Long clientId, Currency currency, LocalDate asOf, BigDecimal balance) {
}
