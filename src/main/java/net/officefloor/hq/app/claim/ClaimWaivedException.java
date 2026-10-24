package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A waiver of the late charge of a claim already waived. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ClaimWaivedException extends RuntimeException {
}
