package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Someone other than a steward asked to reopen a closed period. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PeriodStewardOnlyException extends RuntimeException {

    public PeriodStewardOnlyException() {
        super("Only a steward can reopen a period.");
    }
}
