package net.officefloor.hq.app.person;

import java.time.LocalDate;

/** A person was retired from a date ({@code PERSON_RETIRED}) on a sheet. */
public record PersonRetired(String sheetId, String user, String name, LocalDate from) {
}
