package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A split of an amount off a claim that is not more than 0.00 and less than the claim's amount. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ClaimSplitException extends RuntimeException {
}
