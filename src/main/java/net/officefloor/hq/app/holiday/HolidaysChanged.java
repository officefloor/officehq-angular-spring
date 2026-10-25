package net.officefloor.hq.app.holiday;

/** The sheet's holidays were changed: published once the change is stored, within its transaction, so
 *  every due-by on the sheet is worked out again from the holidays now. */
public record HolidaysChanged(String sheetId, String user) {
}
