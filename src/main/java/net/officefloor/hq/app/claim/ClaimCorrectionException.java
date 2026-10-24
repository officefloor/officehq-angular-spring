package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim with a negative amount (a correction) that does not name, by number, a SETTLED claim of
 * the same person, or a claim that names one without being a correction. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ClaimCorrectionException extends RuntimeException {
}
