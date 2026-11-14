package net.officefloor.hq.app.person;

/** A pasted line of people the person form would refuse, so none of the paste is imported. */
public class ImportRefusedException extends RuntimeException {

    public ImportRefusedException(String message) {
        super(message);
    }
}
