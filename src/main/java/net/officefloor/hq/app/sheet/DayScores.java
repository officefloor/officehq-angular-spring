package net.officefloor.hq.app.sheet;

import java.time.LocalDate;
import java.util.List;
import net.officefloor.hq.app.rollup.Rollup;

/**
 * The sheet's today is about to change while its score log is on: everyone's score as it stood on the day being
 * left, in list order ({@code SCORE}, one line per person), and the day being left. Published before
 * {@link DaySnapshot}.
 */
public record DayScores(String sheetId, String user, LocalDate day, List<Rollup.PersonScore> people) {
}
