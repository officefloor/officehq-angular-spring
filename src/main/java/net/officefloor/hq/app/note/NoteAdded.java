package net.officefloor.hq.app.note;

/** A note was added to a sheet ({@code NOTE_ADDED}). */
public record NoteAdded(String sheetId, String user, String text) {
}
