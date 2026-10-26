package net.officefloor.hq.app.sheet;

/**
 * Published, within its transaction, once a sheet's new today is saved and before the figures it moves are rolled
 * up, so what follows a today change runs in turn with it.
 */
public record TodayChanged(String by, Sheet sheet) {
}
