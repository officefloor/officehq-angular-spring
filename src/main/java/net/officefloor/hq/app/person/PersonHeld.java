package net.officefloor.hq.app.person;

import java.time.LocalDate;

/** A hold from a date was put on a person on a sheet ({@code PERSON_HELD}). */
public record PersonHeld(String sheetId, String user, String name, LocalDate from) {
}
