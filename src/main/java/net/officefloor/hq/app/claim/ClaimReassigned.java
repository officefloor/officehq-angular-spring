package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A claim was moved to another person on a sheet ({@code CLAIM_REASSIGNED}). The person is the claim's
 * new person; the number is the claim's number, or null when it has none; from and to name the people
 * it moved between.
 */
public record ClaimReassigned(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number, String from, String to) {
}
