package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/** An OPEN claim was deferred on a sheet until a date. The number is the claim's number. */
public record ClaimDeferred(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number, LocalDate until) {
}
