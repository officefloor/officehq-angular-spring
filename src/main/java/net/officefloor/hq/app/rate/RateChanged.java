package net.officefloor.hq.app.rate;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A grade's rate was set on a sheet ({@code RATE_SET}); from and to are its from- and to-dates, each null when it has none. */
public record RateChanged(String sheetId, String user, String grade, BigDecimal rate, LocalDate from, LocalDate to) {
}
