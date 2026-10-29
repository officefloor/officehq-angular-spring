package net.officefloor.hq.app.preview;

import java.time.LocalDate;

/**
 * The preview date the request in hand reads its sheet by: while a screen previews a sheet, what it reads is answered
 * as if the sheet's today were that date (see {@link PreviewGuard}); otherwise none.
 */
public final class PreviewContext {

    private record Preview(String sheetId, LocalDate day) {
    }

    private static final ThreadLocal<Preview> PREVIEW = new ThreadLocal<>();

    private PreviewContext() {
    }

    static void enter(String sheetId, LocalDate day) {
        PREVIEW.set(new Preview(sheetId, day));
    }

    static void leave() {
        PREVIEW.remove();
    }

    /** The today the request reads the sheet by: the preview date for the previewed sheet, else its own today. */
    public static LocalDate todayOf(String sheetId, LocalDate today) {
        Preview p = PREVIEW.get();
        return p != null && p.sheetId().equals(sheetId) ? p.day() : today;
    }
}
