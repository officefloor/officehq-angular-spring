package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A person flagged by a user who is not a steward of the sheet. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class FlagForbiddenException extends RuntimeException {

    public FlagForbiddenException(String sheetId, String user) {
        super(user + " is not a steward on sheet " + sheetId + " and may not flag a person");
    }
}
