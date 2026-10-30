package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/** An OPEN or PENDING claim on a sheet was held. The number is the claim's number. */
public record ClaimHeld(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number) {
}
