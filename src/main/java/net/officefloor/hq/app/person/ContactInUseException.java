package net.officefloor.hq.app.person;

/** Another person on the sheet already has this contact. */
public class ContactInUseException extends RuntimeException {

    public ContactInUseException(String contact) {
        super("The contact " + contact + " is already in use on the sheet");
    }
}
