package net.officefloor.hq.app.person;

/** A person on a sheet was given a user as their approver ({@code APPROVER_SET}). */
public record ApproverSet(String sheetId, String user, String name, String approver) {
}
