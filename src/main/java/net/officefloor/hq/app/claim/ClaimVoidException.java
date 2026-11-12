package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A void of a SETTLED claim that is not allowed: by anyone but a steward, or more than 7 days after it
 *  was settled. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimVoidException extends RuntimeException {

    public ClaimVoidException(String message) {
        super(message);
    }
}
