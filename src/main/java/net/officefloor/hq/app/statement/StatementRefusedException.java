package net.officefloor.hq.app.statement;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A statement as of a date after the sheet's today, or before the person was on the sheet. */
@ResponseStatus(HttpStatus.CONFLICT)
public class StatementRefusedException extends RuntimeException {

    public StatementRefusedException(String message) {
        super(message);
    }
}
