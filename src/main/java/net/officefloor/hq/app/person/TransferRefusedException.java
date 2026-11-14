package net.officefloor.hq.app.person;

/** A transfer that may not be made: by anyone but a steward, of a retired person, to the person's own
 *  sheet or an archived one, or of a person whose name, an alias or contact is already on the other
 *  sheet. */
public class TransferRefusedException extends RuntimeException {

    public TransferRefusedException(String message) {
        super(message);
    }
}
