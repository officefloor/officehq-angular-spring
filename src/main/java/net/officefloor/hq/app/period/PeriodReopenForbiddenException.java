package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The reopening of a period by a user who is not a steward of the sheet. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PeriodReopenForbiddenException extends RuntimeException {

    public PeriodReopenForbiddenException(String period, String user) {
        super("Only a steward can reopen the period " + period + "; " + user + " is not one");
    }
}
