package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A reminded claim was escalated on a sheet (once per claim ever). The number is the claim's number. */
public record ClaimEscalated(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number) {
}
