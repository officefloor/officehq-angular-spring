package net.officefloor.hq.app.person;

/**
 * A merge was undone ({@code PERSON_MERGE_UNDONE}): {@code name} is the person restored, {@code from} the person
 * they had been merged into.
 */
public record PersonMergeUndone(String sheetId, String user, String name, String from) {
}
