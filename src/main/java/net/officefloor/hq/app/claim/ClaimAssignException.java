package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An assignment of a claim's review that is not allowed. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimAssignException extends RuntimeException {

    public ClaimAssignException(String message) {
        super(message);
    }
}
