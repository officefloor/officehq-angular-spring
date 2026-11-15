package net.officefloor.hq.app.sheet;

import java.time.LocalDate;

/** Published within the transaction that changes a sheet's today, once the new today is set and
 *  before the figures it moves are recorded. */
public record TodayChanged(String sheetId, String user, LocalDate today) {
}
