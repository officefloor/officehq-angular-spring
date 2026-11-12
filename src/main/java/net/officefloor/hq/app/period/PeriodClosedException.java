package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim dated inside a closed period cannot be added, edited, opened, settled or voided. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PeriodClosedException extends RuntimeException {

    public PeriodClosedException() {
        super("That date is in a closed period.");
    }
}
