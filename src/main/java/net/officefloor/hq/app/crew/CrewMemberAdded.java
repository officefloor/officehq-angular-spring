package net.officefloor.hq.app.crew;

/** A person was added to a crew ({@code CREW_MEMBER_ADDED}). */
public record CrewMemberAdded(String sheetId, String user, String crew, String name) {
}
