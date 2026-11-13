package net.officefloor.hq.app.claim;

/** A claim's external reference must not already be used on its sheet, without regard to case. */
public class RefInUseException extends RuntimeException {

    public RefInUseException(String message) {
        super(message);
    }
}
