package net.officefloor.hq.app.sheet;

import java.time.LocalDate;

/**
 * The sheet's today was changed by a user, published within the change's transaction, so what it
 * sets off is written with it. Carries the sheet's grace days; null when off.
 */
public record TodayChanged(String sheetId, String user, LocalDate today, Integer graceDays) {
}
