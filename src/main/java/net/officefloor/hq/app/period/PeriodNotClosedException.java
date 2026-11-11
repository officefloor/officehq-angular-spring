package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The reopening of a period that is not closed. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PeriodNotClosedException extends RuntimeException {

    public PeriodNotClosedException(String period) {
        super("The period " + period + " is not closed");
    }
}
