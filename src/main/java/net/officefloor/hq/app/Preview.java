package net.officefloor.hq.app;

import java.time.LocalDate;
import java.util.Optional;

/** The preview date a user's screen is set to: while it is set, every figure the screen reads is
 *  worked out as if the sheet's today were that date, over the sheet's current records; every change
 *  still uses the sheet's real today. It belongs to the screen, which sends it in a header with each
 *  request; only reads take it (see {@link PreviewInterceptor}). */
public final class Preview {

    /** The header carrying the screen's preview date (yyyy-mm-dd). */
    public static final String HEADER = "X-Preview";

    private static final ThreadLocal<LocalDate> CURRENT = new ThreadLocal<>();

    private Preview() {
    }

    /** The preview date a header carries; null when it is absent or blank. */
    public static LocalDate parse(String header) {
        return header == null || header.isBlank() ? null : LocalDate.parse(header.trim());
    }

    /** The preview date the read being answered stands in for the sheet's today, if any. */
    public static Optional<LocalDate> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static void set(LocalDate date) {
        if (date == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(date);
        }
    }

    static void clear() {
        CURRENT.remove();
    }
}
