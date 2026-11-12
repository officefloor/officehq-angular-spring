package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The locking of a period already locked, the unlocking of one not locked, or the reopening of one locked. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PeriodLockedException extends RuntimeException {

    public PeriodLockedException(String message) {
        super(message);
    }
}
