package net.officefloor.hq.app.person;

/**
 * A person was transferred between sheets ({@code PERSON_TRANSFERRED}): on the sheet they left, {@code to}
 * names the other sheet; on the sheet they arrived on, {@code from} does.
 */
public record PersonTransferred(String sheetId, String user, String name, String to, String from) {
}
