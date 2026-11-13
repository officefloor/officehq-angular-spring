package net.officefloor.hq.app.claim;

/** A correction (a negative amount) must name a SETTLED claim of the same person; no other claim names one. */
public class CorrectionRefusedException extends RuntimeException {

    public CorrectionRefusedException(String message) {
        super(message);
    }
}
