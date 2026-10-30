package net.officefloor.hq.app.note;

/** A note was refused: nothing was written. */
public class NoteRefused extends RuntimeException {

    public NoteRefused(String message) {
        super(message);
    }
}
