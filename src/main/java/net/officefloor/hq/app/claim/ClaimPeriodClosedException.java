package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A change to a claim dated, or to be dated, inside a closed period. */
@ResponseStatus(HttpStatus.LOCKED)
public class ClaimPeriodClosedException extends RuntimeException {
}
