package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A cancel of a person's last statement that is not allowed: by anyone but a steward, or of a person
 *  with no statement. */
@ResponseStatus(HttpStatus.CONFLICT)
public class StatementCancelException extends RuntimeException {

    public StatementCancelException(String message) {
        super(message);
    }
}
