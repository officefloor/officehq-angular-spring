package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A steward set a DRAFT, OPEN or PENDING claim's due-by on a sheet directly, from an old due-by to a new one.
 * The number is the claim's.
 */
public record ClaimDueBySet(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number, LocalDate old, LocalDate fresh) {
}
