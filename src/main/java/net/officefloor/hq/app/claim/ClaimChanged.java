package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A claim was added ({@code CLAIM_ADDED}), edited ({@code CLAIM_EDITED}), opened
 * ({@code CLAIM_OPENED}), settled ({@code CLAIM_SETTLED}), left pending ({@code CLAIM_PENDING}), approved
 * ({@code CLAIM_APPROVED}), rejected ({@code CLAIM_REJECTED}) or voided ({@code CLAIM_VOIDED}) on a sheet.
 * The number is the claim's number, or null when it has none. The due is the due fixed by settling or
 * approving ({@code n/a} when there was no rate), or null when the event carries none. The reason is a
 * rejection's reason, or null when the event carries none.
 */
public record ClaimChanged(String event, String sheetId, String user, String person, BigDecimal amount,
        LocalDate date, String number, String due, String reason) {

    public ClaimChanged(String event, String sheetId, String user, String person, BigDecimal amount,
            LocalDate date, String number, String due) {
        this(event, sheetId, user, person, amount, date, number, due, null);
    }
}
