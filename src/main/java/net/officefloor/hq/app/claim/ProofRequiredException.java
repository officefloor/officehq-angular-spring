package net.officefloor.hq.app.claim;

import java.math.BigDecimal;

/** A claim whose due is above the proof threshold in force on its date cannot be settled or approved
 *  while it has no proofs, or no proof of a kind the sheet's proof kinds list. */
public class ProofRequiredException extends RuntimeException {

    public ProofRequiredException(BigDecimal due, BigDecimal threshold) {
        super("A claim due " + due + " is above the proof threshold " + threshold + " and has no proofs");
    }

    public ProofRequiredException(BigDecimal due, BigDecimal threshold, String kind) {
        super("A claim due " + due + " is above the proof threshold " + threshold + " and has no " + kind + " proof");
    }
}
