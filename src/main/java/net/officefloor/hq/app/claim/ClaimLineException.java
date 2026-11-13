package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim line that is not allowed: no label, a quantity below 1, a unit amount not of two decimals, or an
 *  edit of a claim with lines to another amount than they add up to. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimLineException extends RuntimeException {

    public ClaimLineException(String message) {
        super(message);
    }
}
