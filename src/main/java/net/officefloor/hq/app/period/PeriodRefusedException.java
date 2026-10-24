package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Adding a period whose name another period on the sheet has, whose to-date is before its
 * from-date, or whose dates overlap another period's. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PeriodRefusedException extends RuntimeException {

    public PeriodRefusedException(String message) {
        super(message);
    }
}
