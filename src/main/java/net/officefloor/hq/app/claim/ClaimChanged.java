package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A claim was added ({@code CLAIM_ADDED}) or edited ({@code CLAIM_EDITED}) on a sheet. */
public record ClaimChanged(String event, String sheetId, String user, String person, BigDecimal amount,
        LocalDate date) {
}
