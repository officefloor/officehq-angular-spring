package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A grade's rate was set on a sheet ({@code RATE_SET}); from is its from-date, or null when it has none. */
public record RateChanged(String sheetId, String user, String grade, BigDecimal rate, LocalDate from) {
}
