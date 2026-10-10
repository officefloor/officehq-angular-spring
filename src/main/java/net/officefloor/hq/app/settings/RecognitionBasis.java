package net.officefloor.hq.app.settings;

/** When revenue counts: when an invoice is sent, or once it is paid. */
public final class RecognitionBasis {

    /** Revenue counts once an invoice is sent. */
    public static final String SENT = "sent";

    /** Revenue counts only once an invoice is fully paid. */
    public static final String PAID = "paid";

    private RecognitionBasis() {
    }
}
