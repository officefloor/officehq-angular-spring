package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The user who opened or last edited a claim asked to settle it: a claim needs a second pair of eyes to be
 *  settled. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimSettlerException extends RuntimeException {

    public ClaimSettlerException(String message) {
        super(message);
    }
}
