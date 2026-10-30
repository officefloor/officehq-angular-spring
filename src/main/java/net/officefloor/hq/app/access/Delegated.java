package net.officefloor.hq.app.access;

import java.time.LocalDate;

/** A steward delegated the steward role to a user name for a range of dates ({@code DELEGATED}). */
public record Delegated(String sheetId, String user, String name, LocalDate from, LocalDate to) {
}
