package net.officefloor.hq.app.person;

/** A user started ({@code PERSON_WATCHED}) or stopped ({@code PERSON_UNWATCHED}) watching a person on a sheet. */
public record PersonWatched(String event, String sheetId, String user, String name) {
}
