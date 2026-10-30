package net.officefloor.hq.app.rate;

import java.math.BigDecimal;

/** A grade's rate was set on a sheet ({@code RATE_SET}). */
public record RateChanged(String sheetId, String user, String grade, BigDecimal rate) {
}
