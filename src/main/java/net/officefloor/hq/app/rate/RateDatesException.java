package net.officefloor.hq.app.rate;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A rate's to-date is before its from-date. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class RateDatesException extends RuntimeException {

    public RateDatesException(LocalDate fromDate, LocalDate toDate) {
        super("The rate's to-date " + toDate + " is before its from-date " + fromDate);
    }
}
