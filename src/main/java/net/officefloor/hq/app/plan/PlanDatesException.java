package net.officefloor.hq.app.plan;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A plan asked with an end before its start, an interval under 1 day or an amount not above zero. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class PlanDatesException extends RuntimeException {
}
