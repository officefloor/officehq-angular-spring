package net.officefloor.hq.app.period;

/** A fiscal year was rolled over into the next ({@code ROLLOVER}): its name and how many periods were made. */
public record RolledOver(String sheetId, String user, String fy, int periods) {
}
