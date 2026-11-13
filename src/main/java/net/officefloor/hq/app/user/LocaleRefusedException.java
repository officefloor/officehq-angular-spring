package net.officefloor.hq.app.user;

/** A locale that cannot be chosen: it is not one the screen knows. */
public class LocaleRefusedException extends RuntimeException {

    public LocaleRefusedException(String message) {
        super(message);
    }
}
