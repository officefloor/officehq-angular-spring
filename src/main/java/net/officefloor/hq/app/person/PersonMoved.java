package net.officefloor.hq.app.person;

/** A person was moved up or down the list ({@code PERSON_MOVED}) on a sheet, to a position counting from 1. */
public record PersonMoved(String sheetId, String user, String name, int position) {
}
