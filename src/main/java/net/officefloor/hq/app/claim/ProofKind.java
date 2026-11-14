package net.officefloor.hq.app.claim;

import java.util.List;

/** The kinds a proof may be: a receipt (the kind of a proof added with none chosen) or a photo. */
public final class ProofKind {

    public static final String RECEIPT = "receipt";
    public static final String PHOTO = "photo";

    /** Every kind, in the order the screen offers them. */
    public static final List<String> ALL = List.of(RECEIPT, PHOTO);

    private ProofKind() {
    }

    /** Whether a kind is one a proof may be. */
    public static boolean valid(String kind) {
        return ALL.contains(kind);
    }
}
