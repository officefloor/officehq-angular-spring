package net.officefloor.hq.app.access;

/** A user name was given a role on a sheet ({@code ROLE_SET}). */
public record RoleSet(String sheetId, String user, String name, String role) {
}
