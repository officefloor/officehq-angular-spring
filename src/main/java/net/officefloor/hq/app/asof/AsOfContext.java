package net.officefloor.hq.app.asof;

import java.time.LocalDate;

/**
 * The date the request being answered shows its sheet as of, set by {@link AsOfFilter} for the length of an as-of
 * read. Only the requested sheet's rows are put back as of the date; what is read of another sheet (a parent sheet's
 * rates) looks it up here to read that as it stood then too.
 */
public final class AsOfContext {

    private static final ThreadLocal<LocalDate> AS_OF = new ThreadLocal<>();

    private AsOfContext() {
    }

    /** The as-of date of the request being answered; null when it shows the sheet as it stands. */
    public static LocalDate current() {
        return AS_OF.get();
    }

    static void set(LocalDate asOf) {
        AS_OF.set(asOf);
    }

    static void clear() {
        AS_OF.remove();
    }
}
