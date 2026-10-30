package net.officefloor.hq.app.person;

/** A user watching a person was told one of their claims was settled, approved or re-settled ({@code NOTIFY}). */
public record WatcherNotified(String sheetId, String user, String watcher, String person, String number) {
}
