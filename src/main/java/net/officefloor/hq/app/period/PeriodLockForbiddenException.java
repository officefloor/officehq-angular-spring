package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The locking or unlocking of a period by a user who is not a steward on the sheet. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PeriodLockForbiddenException extends RuntimeException {

    public PeriodLockForbiddenException(String period, String user) {
        super("The period " + period + " cannot be locked or unlocked by " + user + ": only a steward can");
    }
}
