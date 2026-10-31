package net.officefloor.hq.app.rate;

/** A grade was added ({@code GRADE_ADDED}) to a sheet. */
public record GradeAdded(String sheetId, String user, String grade) {
}
