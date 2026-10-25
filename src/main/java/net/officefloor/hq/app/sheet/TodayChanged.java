package net.officefloor.hq.app.sheet;

import java.time.LocalDate;

/** The sheet's today was changed: published once the new today is stored, before the figures it moves
 *  are audited, within the change's transaction. */
public record TodayChanged(String sheetId, String user, LocalDate today) {
}
