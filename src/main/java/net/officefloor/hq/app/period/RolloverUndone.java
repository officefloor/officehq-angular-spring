package net.officefloor.hq.app.period;

/** The last rollover of a fiscal year was undone ({@code ROLLOVER_UNDONE}): the old year's name. */
public record RolloverUndone(String sheetId, String user, String fy) {
}
