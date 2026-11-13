package net.officefloor.hq.app.claim;

/** A proof cannot be added to a SETTLED or VOID claim. */
public class ProofRefusedException extends RuntimeException {

    public ProofRefusedException(ClaimStatus status) {
        super("A proof cannot be added to a " + status + " claim");
    }
}
