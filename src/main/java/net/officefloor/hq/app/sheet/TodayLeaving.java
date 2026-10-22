package net.officefloor.hq.app.sheet;

import java.time.LocalDate;

/**
 * The sheet's today is about to move on from a date, published within the change's transaction
 * before the sheet's today changes, so the sheet can be kept as it stood at the end of that date.
 */
public record TodayLeaving(String sheetId, LocalDate today) {
}
