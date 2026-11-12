package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The rolling over of a fiscal year by a user who is not a steward on the sheet. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class RolloverForbiddenException extends RuntimeException {

    public RolloverForbiddenException(String fy, String user) {
        super("The fiscal year " + fy + " cannot be rolled over by " + user + ": only a steward can");
    }
}
