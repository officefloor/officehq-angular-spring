package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A claim was shared with another person on a sheet ({@code CLAIM_SHARED}). The person is the claim's own
 * person; the number is the claim's number, or null when it has none; with names the person now holding the
 * share, a percent.
 */
public record ClaimShared(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number, String with, BigDecimal share) {
}
