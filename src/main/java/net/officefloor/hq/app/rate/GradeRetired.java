package net.officefloor.hq.app.rate;

/** A grade was retired ({@code GRADE_RETIRED}) on a sheet. */
public record GradeRetired(String sheetId, String user, String grade) {
}
