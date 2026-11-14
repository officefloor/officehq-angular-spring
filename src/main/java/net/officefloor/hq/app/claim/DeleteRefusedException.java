package net.officefloor.hq.app.claim;

/** A claim that cannot be deleted or restored: only a DRAFT is deleted, and only a DELETED claim is
 *  restored, on the sheet's today it was deleted. */
public class DeleteRefusedException extends RuntimeException {

    public DeleteRefusedException(String message) {
        super(message);
    }
}
