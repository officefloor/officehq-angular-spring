package net.officefloor.hq.app.holiday;

import java.time.LocalDate;

/** A holiday was added to a sheet ({@code HOLIDAY_ADDED}). */
public record HolidayAdded(String sheetId, String user, LocalDate date) {
}
