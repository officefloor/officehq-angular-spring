package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A comment was added to a claim on a sheet. The number is the claim's number (null while it has none). */
public record ClaimCommented(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number, String text) {
}
