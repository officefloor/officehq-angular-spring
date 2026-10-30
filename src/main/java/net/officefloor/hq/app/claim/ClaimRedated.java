package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An OPEN or PENDING claim was re-dated on a sheet from an old date to a new one. The date and number are
 * the claim's after the change.
 */
public record ClaimRedated(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number, LocalDate old, LocalDate fresh) {
}
