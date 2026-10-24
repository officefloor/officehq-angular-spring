package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A settle or approval of a claim whose due is above the proof threshold in force on its date, while it
 * has no proofs. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ClaimProofRequiredException extends RuntimeException {
}
