package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An open of a claim whose person already has as many OPEN or PENDING claims in its period as the period's
 *  cap. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimCapException extends RuntimeException {

    public ClaimCapException(String message) {
        super(message);
    }
}
