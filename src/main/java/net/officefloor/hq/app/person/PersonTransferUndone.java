package net.officefloor.hq.app.person;

/**
 * A transfer was undone ({@code PERSON_TRANSFER_UNDONE}) on the sheet it had brought the person to:
 * {@code to} names the sheet they were transferred back to.
 */
public record PersonTransferUndone(String sheetId, String user, String name, String to) {
}
