package net.officefloor.hq.app.history;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The preview date a user's screen shows the sheet's figures on, sent in the {@link #HEADER} header
 * (yyyy-mm-dd) on every request while it is set. Reads of figures take it as the sheet's today, over the
 * sheet's current records; every change ignores it and uses the sheet's real today, and nothing is kept,
 * scored or closed for it, as the sheet's today in the store does not move.
 */
public final class PreviewDate {

    public static final String HEADER = "X-Preview";

    private PreviewDate() {
    }

    /** The preview date from the header's value; null when none is set. */
    public static LocalDate date(String header) {
        String text = header == null ? "" : header.trim();
        try {
            return text.isEmpty() ? null : LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a preview date: " + text);
        }
    }

    /** The day figures are read on: none while an as-of date is set (it decides), else the preview date. */
    public static LocalDate date(String asOfHeader, String previewHeader) {
        return AsOfGuard.date(asOfHeader) != null ? null : date(previewHeader);
    }
}
