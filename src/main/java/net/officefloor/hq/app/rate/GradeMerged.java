package net.officefloor.hq.app.rate;

/** A grade was merged into another ({@code GRADE_MERGED}) on a sheet. */
public record GradeMerged(String sheetId, String user, String from, String into) {
}
