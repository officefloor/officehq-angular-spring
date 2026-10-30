package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A held OPEN or PENDING claim on a sheet was released. The number is the claim's number. */
public record ClaimReleased(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number) {
}
