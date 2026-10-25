package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Undoing a rollover that cannot be undone: there is none left to undo, it was made on another sheet-today
 * or by another user, or a claim now belongs to a period it generated. */
@ResponseStatus(HttpStatus.CONFLICT)
public class RolloverUndoException extends RuntimeException {

    public RolloverUndoException(String message) {
        super(message);
    }
}
