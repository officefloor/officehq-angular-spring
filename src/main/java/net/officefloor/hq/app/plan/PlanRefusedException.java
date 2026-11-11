package net.officefloor.hq.app.plan;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A plan that may not be added. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PlanRefusedException extends RuntimeException {

    public PlanRefusedException(String message) {
        super(message);
    }
}
