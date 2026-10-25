package net.officefloor.hq.app.plan;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A plan's amount changed to one not above zero. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class PlanAmountException extends RuntimeException {
}
