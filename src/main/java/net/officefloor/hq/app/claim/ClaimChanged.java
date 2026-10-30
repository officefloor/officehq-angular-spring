package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A claim was added ({@code CLAIM_ADDED}), added while its period was soft-closed ({@code LATE_ENTRY}, after its
 * {@code CLAIM_ADDED}), edited ({@code CLAIM_EDITED}), opened
 * ({@code CLAIM_OPENED}), settled ({@code CLAIM_SETTLED}), left pending ({@code CLAIM_PENDING}), approved
 * ({@code CLAIM_APPROVED}), rejected ({@code CLAIM_REJECTED}) or voided ({@code CLAIM_VOIDED}) on a sheet.
 * The number is the claim's number, or null when it has none. The due is the due fixed by settling or
 * approving ({@code n/a} when there was no rate), or null when the event carries none. The reason is a
 * rejection's reason, or null when the event carries none. The reversed is the frozen figure a voided
 * SETTLED claim takes out of the totals ({@code n/a} when it had none), or null when the event carries none.
 * The ref is an added claim's external reference, or null when it has none or the event carries none. The
 * tags are an added claim's tags, sorted and comma separated, or null when it has none or the event carries none.
 * The void reason is the reason from the sheet's list a claim was voided with, or null when none was given.
 */
public record ClaimChanged(String event, String sheetId, String user, String person, BigDecimal amount,
        LocalDate date, String number, String due, String reason, String reversed, String ref, String tags,
        String voidReason) {

    public ClaimChanged(String event, String sheetId, String user, String person, BigDecimal amount,
            LocalDate date, String number, String due, String reason, String reversed, String ref, String tags) {
        this(event, sheetId, user, person, amount, date, number, due, reason, reversed, ref, tags, null);
    }

    public ClaimChanged(String event, String sheetId, String user, String person, BigDecimal amount,
            LocalDate date, String number, String due, String reason, String reversed, String ref) {
        this(event, sheetId, user, person, amount, date, number, due, reason, reversed, ref, null);
    }

    public ClaimChanged(String event, String sheetId, String user, String person, BigDecimal amount,
            LocalDate date, String number, String due, String reason, String reversed) {
        this(event, sheetId, user, person, amount, date, number, due, reason, reversed, null);
    }

    public ClaimChanged(String event, String sheetId, String user, String person, BigDecimal amount,
            LocalDate date, String number, String due, String reason) {
        this(event, sheetId, user, person, amount, date, number, due, reason, null);
    }

    public ClaimChanged(String event, String sheetId, String user, String person, BigDecimal amount,
            LocalDate date, String number, String due) {
        this(event, sheetId, user, person, amount, date, number, due, null);
    }
}
