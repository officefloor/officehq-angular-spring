package net.officefloor.hq.app.history;

/**
 * A screen previewing the sheet on a date: what it reads is the sheet's rows as they are now, every figure
 * reckoned as if the sheet's today were that date. It belongs to the screen alone: it is sent only with what the
 * screen reads, never with a change, so every change still uses the sheet's real today and nothing is kept of it.
 */
public final class Preview {

    /** The header a screen previewing the sheet on a date (YYYY-MM-DD) sends with each read. */
    public static final String HEADER = "X-Preview";

    private Preview() {
    }
}
