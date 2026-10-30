package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A steward assigned a REVIEW claim on a sheet to a user ({@code REVIEW_ASSIGNED}). The number is the claim's
 * number, or null when it has none; assignee names the user it is assigned to.
 */
public record ReviewAssigned(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number, String assignee) {
}
