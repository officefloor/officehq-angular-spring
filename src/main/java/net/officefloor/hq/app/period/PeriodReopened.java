package net.officefloor.hq.app.period;

/** A closed period on a sheet was reopened, making its due-total live again ({@code PERIOD_REOPENED}). */
public record PeriodReopened(String sheetId, String user, String name) {
}
