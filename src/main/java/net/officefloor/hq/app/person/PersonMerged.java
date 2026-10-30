package net.officefloor.hq.app.person;

/** A person on a sheet was merged into another ({@code PERSON_MERGED}). */
public record PersonMerged(String sheetId, String user, String from, String into) {
}
