package net.officefloor.hq.app.person;

/** A person was added ({@code PERSON_ADDED}) or edited ({@code PERSON_EDITED}) on a sheet. */
public record PersonChanged(String event, String sheetId, String user, String name, String grade) {
}
