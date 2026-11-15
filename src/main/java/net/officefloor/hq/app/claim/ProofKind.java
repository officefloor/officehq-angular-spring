package net.officefloor.hq.app.claim;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The kind of a proof: a receipt (when none is chosen) or a photo. */
public enum ProofKind {
    RECEIPT, PHOTO;

    /** The kind as it is typed and audited: receipt or photo. */
    public String text() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The kind named by its text; empty when none is. */
    public static Optional<ProofKind> of(String text) {
        return Arrays.stream(values()).filter(k -> k.text().equals(text)).findFirst();
    }
}
