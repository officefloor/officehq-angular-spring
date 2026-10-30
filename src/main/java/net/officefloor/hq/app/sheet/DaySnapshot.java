package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import java.util.Map;
import net.officefloor.hq.app.rollup.Rollup;

/**
 * The sheet's today is about to change: everyone's figures as they stood on the day being left, in the
 * order they were added ({@code SNAPSHOT}, one line per person) by person id, and the day being left. Published before
 * {@link TodayChanged}.
 */
public record DaySnapshot(String sheetId, String user, LocalDate day, Map<Long, Rollup.PersonFigures> people) {
}
