package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A statement as of a date that cannot be taken: a date after the sheet's today, or one before its person
 *  was on the sheet. */
@ResponseStatus(HttpStatus.CONFLICT)
public class StatementRefusedException extends RuntimeException {

    public StatementRefusedException(String message) {
        super(message);
    }
}
