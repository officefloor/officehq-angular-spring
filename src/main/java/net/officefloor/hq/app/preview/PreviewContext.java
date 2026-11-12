package net.officefloor.hq.app.preview;

import java.time.LocalDate;

/**
 * The date the request being answered previews its sheet on, set by {@link PreviewFilter} for the length of a
 * preview read. The requested sheet's today is that date for the read; what is read of another sheet (a descendant
 * sheet's figures) looks it up here to read that as if its today were the date too.
 */
public final class PreviewContext {

    private static final ThreadLocal<LocalDate> PREVIEW = new ThreadLocal<>();

    private PreviewContext() {
    }

    /** The preview date of the request being answered; null when it shows the sheet under its own today. */
    public static LocalDate current() {
        return PREVIEW.get();
    }

    static void set(LocalDate preview) {
        PREVIEW.set(preview);
    }

    static void clear() {
        PREVIEW.remove();
    }
}
