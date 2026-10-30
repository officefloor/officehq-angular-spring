package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A line was added to a claim on a sheet, giving it the amount. The number is the claim's number (null while it
 * has none).
 */
public record ClaimLineAdded(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number, String label, int qty, BigDecimal unit) {
}
