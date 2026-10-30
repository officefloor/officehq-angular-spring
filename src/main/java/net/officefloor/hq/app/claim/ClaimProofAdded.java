package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A proof was added to a claim on a sheet, giving it the proofs count, of the kind chosen (null when none was). The number is the claim's number. */
public record ClaimProofAdded(String sheetId, String user, String person, BigDecimal amount, LocalDate date,
        String number, int proofs, String kind) {
}
