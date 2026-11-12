package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Another user set the grade's rate at the same moment. */
@ResponseStatus(HttpStatus.CONFLICT)
public class RateConflictException extends RuntimeException {

    public RateConflictException() {
        super("The rate was changed at the same moment; try again.");
    }
}
