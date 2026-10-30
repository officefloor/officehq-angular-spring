package net.officefloor.hq.app.sheet;

import java.time.LocalDate;

/**
 * The sheet's today was changed and saved; published inside the change, before the figures it moves are
 * announced, so what follows from the new today is part of the same change. No audit line of its own.
 */
public record TodayChanged(String sheetId, String user, LocalDate today) {
}
