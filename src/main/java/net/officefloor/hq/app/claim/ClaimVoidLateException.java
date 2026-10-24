package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A void of a SETTLED claim asked more than 7 days after its settlement. */
@ResponseStatus(HttpStatus.GONE)
public class ClaimVoidLateException extends RuntimeException {
}
