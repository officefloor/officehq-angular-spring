package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A period that cannot be added: its name is taken, its dates run backwards, or it overlaps another. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PeriodRefusedException extends RuntimeException {

    public PeriodRefusedException(String message) {
        super(message);
    }
}
