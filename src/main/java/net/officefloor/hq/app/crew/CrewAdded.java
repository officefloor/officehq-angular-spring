package net.officefloor.hq.app.crew;

/** A crew was added to a sheet with its lead ({@code CREW_ADDED}). */
public record CrewAdded(String sheetId, String user, String name, String lead) {
}
