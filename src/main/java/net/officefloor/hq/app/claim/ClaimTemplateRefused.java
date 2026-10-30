package net.officefloor.hq.app.claim;

/** A claim template, or a claim from one, was refused: nothing was written. */
public class ClaimTemplateRefused extends RuntimeException {

    public ClaimTemplateRefused(String message) {
        super(message);
    }
}
