package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A share of a claim refused: given to the claim's own person or to a person already holding one, taking
 * its shares over 100.00, or of a SETTLED, VOID or DELETED claim. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ClaimShareException extends RuntimeException {
}
