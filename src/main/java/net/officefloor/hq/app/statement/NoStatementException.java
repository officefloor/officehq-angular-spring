package net.officefloor.hq.app.statement;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The cancelling of the last statement of a person who has none. */
@ResponseStatus(HttpStatus.CONFLICT)
public class NoStatementException extends RuntimeException {

    public NoStatementException(String sheetId, Long personId) {
        super("Person " + personId + " on sheet " + sheetId + " has no statement to cancel");
    }
}
