package net.officefloor.hq.app.person;

import java.time.LocalDate;

/** A retired person was unretired on the sheet's today ({@code PERSON_UNRETIRED}) on a sheet. */
public record PersonUnretired(String sheetId, String user, String name, LocalDate on) {
}
