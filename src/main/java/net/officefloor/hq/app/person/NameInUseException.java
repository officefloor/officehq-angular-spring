package net.officefloor.hq.app.person;

/** Another person on the sheet already has this name (ignoring case and surrounding spaces). */
public class NameInUseException extends RuntimeException {

    public NameInUseException(String name) {
        super("The name " + name + " is already in use on the sheet");
    }
}
