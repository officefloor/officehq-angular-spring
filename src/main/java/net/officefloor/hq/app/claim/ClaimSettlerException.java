package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The user who opened a claim asked to settle it: a claim needs a second pair of eyes to be settled. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimSettlerException extends RuntimeException {

    public ClaimSettlerException() {
        super("The user who opened a claim cannot settle it.");
    }
}
