package net.officefloor.hq.app.settings;

import java.time.LocalDate;

/** Published within the transaction that adds a holiday to a sheet, once it is on the sheet's list
 *  and its HOLIDAY_ADDED record is written. */
public record HolidayAdded(String sheetId, String user, LocalDate day) {
}
