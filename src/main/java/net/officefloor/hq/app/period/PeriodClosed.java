package net.officefloor.hq.app.period;

import java.math.BigDecimal;

/** A period on a sheet was closed, fixing its due-total ({@code PERIOD_CLOSED}). */
public record PeriodClosed(String sheetId, String user, String name, BigDecimal total) {
}
