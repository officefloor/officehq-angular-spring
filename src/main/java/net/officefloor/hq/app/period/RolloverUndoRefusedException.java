package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The undoing of a rollover refused: none to undo, a later day than it was made, a user other than the steward who made
 * it, or a period it generated that a claim now belongs to.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class RolloverUndoRefusedException extends RuntimeException {

    public RolloverUndoRefusedException(String message) {
        super(message);
    }
}
