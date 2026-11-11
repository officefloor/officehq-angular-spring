package net.officefloor.hq.app.statement;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The cancelling of a person's last statement by a user who is not a steward of the sheet. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class StatementCancelForbiddenException extends RuntimeException {

    public StatementCancelForbiddenException(String sheetId, String user) {
        super("A statement on sheet " + sheetId + " cannot be cancelled by " + user + ": only a steward may");
    }
}
