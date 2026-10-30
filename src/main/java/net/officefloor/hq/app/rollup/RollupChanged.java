package net.officefloor.hq.app.rollup;

import java.util.List;

/**
 * A change moved figures on a sheet: each person whose claimed, due-total or overdue-total changed ({@code ROLLUP}),
 * in the order they were added, and the sheet's totals ({@code TOTALS}) when they changed, else null; then each person
 * whose due-total the change took above the sheet's alert threshold ({@code ALERT}), in list order.
 */
public record RollupChanged(String sheetId, String user, List<Rollup.PersonFigures> people, Rollup.Totals totals,
        List<Rollup.PersonFigures> alerts) {
}
