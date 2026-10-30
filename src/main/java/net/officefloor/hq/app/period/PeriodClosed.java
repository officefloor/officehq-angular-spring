package net.officefloor.hq.app.period;

import java.math.BigDecimal;

/**
 * A period on a sheet was closed, fixing its due-total ({@code PERIOD_CLOSED}); auto when it was closed by a
 * change of the sheet's today, past its grace days, rather than by hand.
 */
public record PeriodClosed(String sheetId, String user, String name, BigDecimal total, boolean auto) {
}
