package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A reassignment the claim does not allow: it is SETTLED or VOID, or already the person's. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimReassignException extends RuntimeException {

    public ClaimReassignException(Long id, String why) {
        super("Claim " + id + " cannot be reassigned: " + why);
    }
}
