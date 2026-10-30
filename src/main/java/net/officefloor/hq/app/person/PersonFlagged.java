package net.officefloor.hq.app.person;

import java.time.LocalDate;

/** A steward flagged a person on a sheet with a reason until a date ({@code PERSON_FLAGGED}). */
public record PersonFlagged(String sheetId, String user, String name, String reason, LocalDate until) {
}
