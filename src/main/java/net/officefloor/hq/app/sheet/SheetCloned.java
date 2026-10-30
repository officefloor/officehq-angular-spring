package net.officefloor.hq.app.sheet;

/**
 * A steward cloned a sheet to another id: on the sheet cloned ({@code SHEET_CLONE_MADE}), {@code to} names the
 * clone; on the clone ({@code SHEET_CLONED}), {@code from} names the sheet it was cloned from.
 */
public record SheetCloned(String sheetId, String user, String to, String from) {
}
