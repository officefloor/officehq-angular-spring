package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A SETTLED claim was re-settled on a sheet, its fixed due moved from {@code old} to {@code fresh}
 * (either null when there was no rate). The number is the claim's number.
 */
public record ClaimResettled(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number, BigDecimal old, BigDecimal fresh) {
}
