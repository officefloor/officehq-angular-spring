package net.officefloor.hq.app.person;

/** A person was put on hold ({@code PERSON_HELD}) or taken off it ({@code PERSON_RELEASED}) on a sheet. */
public record PersonHeld(String event, String sheetId, String user, String name) {
}
