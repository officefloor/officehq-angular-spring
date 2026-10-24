package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Setting a rate whose to-date is before its from-date. */
@ResponseStatus(HttpStatus.CONFLICT)
public class RateRefusedException extends RuntimeException {

    public RateRefusedException(String message) {
        super(message);
    }
}
