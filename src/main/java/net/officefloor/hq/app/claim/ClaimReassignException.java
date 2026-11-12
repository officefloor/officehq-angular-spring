package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim cannot be moved to the person asked for, or not by this user. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimReassignException extends RuntimeException {

    public ClaimReassignException(String message) {
        super(message);
    }
}
