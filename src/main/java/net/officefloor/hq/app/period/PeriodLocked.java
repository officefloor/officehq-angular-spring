package net.officefloor.hq.app.period;

/** A steward locked a period on a sheet, so it cannot be reopened ({@code PERIOD_LOCKED}). */
public record PeriodLocked(String sheetId, String user, String name) {
}
