package net.officefloor.hq.app.claim;

/** A proof cannot be added to a SETTLED or VOID claim, nor be of a kind other than receipt or photo. */
public class ProofRefusedException extends RuntimeException {

    public ProofRefusedException(ClaimStatus status) {
        super("A proof cannot be added to a " + status + " claim");
    }

    public ProofRefusedException(String kind) {
        super("A proof is a receipt or a photo, not " + kind);
    }
}
