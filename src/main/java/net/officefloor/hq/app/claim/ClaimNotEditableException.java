package net.officefloor.hq.app.claim;

/** Only a DRAFT claim can be edited; an OPEN one is voided and added again instead. */
public class ClaimNotEditableException extends RuntimeException {

    public ClaimNotEditableException(ClaimStatus status) {
        super("A " + status + " claim cannot be edited");
    }
}
