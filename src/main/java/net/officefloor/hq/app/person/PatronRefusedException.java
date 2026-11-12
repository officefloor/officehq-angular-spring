package net.officefloor.hq.app.person;

/** A patron a person cannot name: themselves, or someone already their patron. */
public class PatronRefusedException extends RuntimeException {

    public PatronRefusedException(String message) {
        super(message);
    }
}
