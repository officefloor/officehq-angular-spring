package net.officefloor.hq.app.person;

/**
 * A person was added ({@code PERSON_ADDED}) or edited ({@code PERSON_EDITED}) on a sheet; terms: the
 * terms the change gave, "" when it cleared them, null when it left them out or as they were.
 */
public record PersonChanged(String event, String sheetId, String user, String name, String grade, String terms) {
}
