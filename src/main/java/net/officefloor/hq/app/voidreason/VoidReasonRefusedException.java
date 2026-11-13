package net.officefloor.hq.app.voidreason;

/** A void reason that cannot be added: it is blank, or already on the sheet's list. */
public class VoidReasonRefusedException extends RuntimeException {

    public VoidReasonRefusedException(String message) {
        super(message);
    }
}
