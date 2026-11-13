package net.officefloor.hq.app.note;

/** A note that cannot be added: its text is blank. */
public class NoteRefusedException extends RuntimeException {

    public NoteRefusedException(String message) {
        super(message);
    }
}
