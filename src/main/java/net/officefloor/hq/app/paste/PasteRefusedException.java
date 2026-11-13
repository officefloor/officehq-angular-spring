package net.officefloor.hq.app.paste;

/** A pasted line that the claim form would refuse: nothing of the paste is added. */
public class PasteRefusedException extends RuntimeException {

    public PasteRefusedException(String message) {
        super(message);
    }
}
