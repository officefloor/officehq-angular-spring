package net.officefloor.hq.app.person;

/** A person on a sheet named another person on it as their patron ({@code PATRON_ADDED}). */
public record PatronAdded(String sheetId, String user, String name, String patron) {
}
