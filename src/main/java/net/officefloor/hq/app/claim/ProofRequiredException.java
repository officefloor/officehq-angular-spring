package net.officefloor.hq.app.claim;

import java.math.BigDecimal;

/** A claim whose due is above the proof threshold in force on its date cannot be settled or approved
 *  while it has no proofs. */
public class ProofRequiredException extends RuntimeException {

    public ProofRequiredException(BigDecimal due, BigDecimal threshold) {
        super("A claim due " + due + " is above the proof threshold " + threshold + " and has no proofs");
    }
}
