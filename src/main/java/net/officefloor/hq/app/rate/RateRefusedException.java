package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A rate that cannot be set as asked, such as one whose to-date is before its from-date. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class RateRefusedException extends RuntimeException {

    public RateRefusedException(String message) {
        super(message);
    }
}
