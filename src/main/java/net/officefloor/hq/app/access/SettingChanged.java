package net.officefloor.hq.app.access;

import java.time.LocalDate;

/**
 * A sheet's setting was saved ({@code SETTING_CHANGED}); value is as written, blank for none; from is the
 * from-date given for a dated setting, null when none was given (the sheet's today).
 */
public record SettingChanged(String sheetId, String user, String key, String value, LocalDate from) {
}
