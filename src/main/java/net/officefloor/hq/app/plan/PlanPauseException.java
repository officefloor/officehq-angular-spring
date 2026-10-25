package net.officefloor.hq.app.plan;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pause asked of a plan already paused, or a resume of one that is running. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PlanPauseException extends RuntimeException {
}
