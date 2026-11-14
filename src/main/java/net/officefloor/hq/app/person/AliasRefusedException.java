package net.officefloor.hq.app.person;

/** An alias that may not be added: a blank one. */
public class AliasRefusedException extends RuntimeException {

    public AliasRefusedException(String message) {
        super(message);
    }
}
