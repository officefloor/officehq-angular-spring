package net.officefloor.hq.app.access;

/** A sheet's setting was saved ({@code SETTING_CHANGED}); value is as written, blank for none. */
public record SettingChanged(String sheetId, String user, String key, String value) {
}
