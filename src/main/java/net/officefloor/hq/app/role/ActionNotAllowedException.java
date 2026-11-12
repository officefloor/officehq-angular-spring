package net.officefloor.hq.app.role;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The user's role on the sheet may not take the action, by the sheet's permission matrix. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ActionNotAllowedException extends RuntimeException {

    public ActionNotAllowedException(String action) {
        super("Your role may not " + action + " here.");
    }
}
