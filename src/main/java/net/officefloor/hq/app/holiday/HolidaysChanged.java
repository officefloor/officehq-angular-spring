package net.officefloor.hq.app.holiday;

import java.time.LocalDate;

/** The sheet's holidays were changed by adding the holiday on the date: published once the change is
 *  stored, within its transaction, so the due-bys it reaches are worked out again from the holidays now. */
public record HolidaysChanged(String sheetId, String user, LocalDate date) {
}
