package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A deferral of a claim to a date that is not after the sheet's today. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ClaimDeferException extends RuntimeException {
}
