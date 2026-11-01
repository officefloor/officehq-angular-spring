package net.officefloor.hq.app.sheet;

import java.time.LocalDate;

/** A screen's preview date: while one is set, every read the screen makes carries it in the {@link #HEADER} header,
 *  and the figures it answers with are worked out over the sheet's current records as if that date were the sheet's
 *  today. Changes ignore it: each still uses the sheet's real today. */
public final class Preview {

    public static final String HEADER = "X-Preview";

    private Preview() {
    }

    /** The sheet as the screen reads it: previewed on the date when one is set, else as it is. */
    public static Sheet of(Sheet sheet, LocalDate preview) {
        return sheet == null || preview == null ? sheet : sheet.previewedOn(preview);
    }

    /** The today the screen reads the sheet with: the preview date when one is set, else the sheet's today. */
    public static LocalDate today(LocalDate today, LocalDate preview) {
        return preview != null ? preview : today;
    }
}
