package net.officefloor.hq.app.person;

/** An alias a person cannot be given: blank, or equal to a name or alias already on the sheet. */
public class AliasRefusedException extends RuntimeException {

    public AliasRefusedException(String message) {
        super(message);
    }
}
