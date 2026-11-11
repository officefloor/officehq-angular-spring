package net.officefloor.hq.app.period;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A change to a claim dated inside a closed period, or the closing of a period already closed. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PeriodClosedException extends RuntimeException {

    public PeriodClosedException(String period) {
        super("The period " + period + " is closed");
    }

    public PeriodClosedException(String period, LocalDate date) {
        super("The date " + date + " is in the closed period " + period);
    }
}
