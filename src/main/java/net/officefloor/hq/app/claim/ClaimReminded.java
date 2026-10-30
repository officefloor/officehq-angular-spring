package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/** An OPEN or PENDING claim was reminded on a sheet. The number is the claim's number. */
public record ClaimReminded(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number) {
}
