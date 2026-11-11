package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The closing of a period by a user whose role the sheet's permission matrix does not allow to close. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PeriodCloseForbiddenException extends RuntimeException {

    public PeriodCloseForbiddenException(String period, String user) {
        super("The period " + period + " cannot be closed by " + user + ": the role is not allowed to close");
    }
}
