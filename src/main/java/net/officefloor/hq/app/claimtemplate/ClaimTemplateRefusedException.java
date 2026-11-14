package net.officefloor.hq.app.claimtemplate;

/** A claim template that cannot be saved: its name is blank, or its amount is one the claim form refuses. */
public class ClaimTemplateRefusedException extends RuntimeException {

    public ClaimTemplateRefusedException(String message) {
        super(message);
    }
}
