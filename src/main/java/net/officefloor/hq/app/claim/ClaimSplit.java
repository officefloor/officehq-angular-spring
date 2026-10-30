package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An OPEN claim was split on a sheet ({@code CLAIM_SPLIT}). The amount is the amount the claim kept; the
 * number is the claim's number; into is the number of the new claim split off it.
 */
public record ClaimSplit(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number, String into) {
}
