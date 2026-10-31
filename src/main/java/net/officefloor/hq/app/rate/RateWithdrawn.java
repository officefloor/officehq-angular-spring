package net.officefloor.hq.app.rate;

import java.time.LocalDate;

/** A grade's newest rate version for a from-date was withdrawn ({@code RATE_WITHDRAWN}); from is null when it has none. */
public record RateWithdrawn(String sheetId, String user, String grade, LocalDate from) {
}
