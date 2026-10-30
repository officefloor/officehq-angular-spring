package net.officefloor.hq.app.period;

/** A steward unlocked a locked period on a sheet ({@code PERIOD_UNLOCKED}). */
public record PeriodUnlocked(String sheetId, String user, String name) {
}
