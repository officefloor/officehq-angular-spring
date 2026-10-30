package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A steward waived the late charge of an OPEN or PENDING claim on a sheet. The number is the claim's number. */
public record ClaimWaived(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number) {
}
