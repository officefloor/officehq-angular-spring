package net.officefloor.hq.app.claim;

import java.util.Locale;

/** The kind of a proof: a receipt (when none is chosen) or a photo. */
public enum ProofKind {
    RECEIPT, PHOTO;

    /** The kind as the screen and the audit file name it: receipt or photo. */
    public String token() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The kind named receipt or photo. */
    public static ProofKind of(String token) {
        return valueOf(token.trim().toUpperCase(Locale.ROOT));
    }
}
