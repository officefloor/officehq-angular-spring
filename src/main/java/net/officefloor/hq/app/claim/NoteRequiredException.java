package net.officefloor.hq.app.claim;

import java.math.BigDecimal;

/** A claim whose due is above the note threshold in force on its date cannot be opened while its note
 *  is empty. */
public class NoteRequiredException extends RuntimeException {

    public NoteRequiredException(BigDecimal due, BigDecimal threshold) {
        super("A claim due " + due + " is above the note threshold " + threshold + " and has no note");
    }
}
