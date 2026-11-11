package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A period whose name is taken on the sheet, whose dates overlap another period's, or whose to-date is before its from-date. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PeriodRefusedException extends RuntimeException {

    public PeriodRefusedException(String message) {
        super(message);
    }
}
