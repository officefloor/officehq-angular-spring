package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The reopening of a period by a user whose role the sheet's permission matrix does not allow to reopen. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PeriodReopenForbiddenException extends RuntimeException {

    public PeriodReopenForbiddenException(String period, String user) {
        super("The period " + period + " cannot be reopened by " + user + ": the role is not allowed to reopen");
    }
}
