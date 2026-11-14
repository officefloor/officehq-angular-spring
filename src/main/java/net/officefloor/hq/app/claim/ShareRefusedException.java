package net.officefloor.hq.app.claim;

/** A share of a claim that is refused: the claim is SETTLED, VOID or DELETED, the holder is the
 *  claim's own person or already holds a share, the share is not more than 0.00, or the shares would
 *  total more than 100.00. */
public class ShareRefusedException extends RuntimeException {

    public ShareRefusedException(String message) {
        super(message);
    }
}
