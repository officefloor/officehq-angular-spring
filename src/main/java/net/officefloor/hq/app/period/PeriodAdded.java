package net.officefloor.hq.app.period;

import java.time.LocalDate;

/** A period was added to a sheet ({@code PERIOD_ADDED}). */
public record PeriodAdded(String sheetId, String user, String name, LocalDate from, LocalDate to) {
}
