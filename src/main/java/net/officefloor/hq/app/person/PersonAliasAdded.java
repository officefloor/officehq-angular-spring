package net.officefloor.hq.app.person;

/** A person on a sheet was given an alias ({@code PERSON_ALIAS_ADDED}). */
public record PersonAliasAdded(String sheetId, String user, String name, String alias) {
}
