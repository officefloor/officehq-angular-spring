package net.officefloor.hq.app;

import java.time.LocalDate;

/** The as-of date a user's screen is set to: while it is set, everything the screen reads is as it
 *  stood at the end of that day and every change is refused. It belongs to the screen, which sends it
 *  in a header with each request; a request without it reads the sheet as it is now. */
public final class AsOf {

    /** The header carrying the screen's as-of date (yyyy-mm-dd). */
    public static final String HEADER = "X-As-Of";

    private AsOf() {
    }

    /** The as-of date a header carries; null when it is absent or blank (the screen shows now). */
    public static LocalDate parse(String header) {
        return header == null || header.isBlank() ? null : LocalDate.parse(header.trim());
    }
}
