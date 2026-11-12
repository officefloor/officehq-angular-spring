package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An edit of a claim whose editor another user has open. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimLockedException extends RuntimeException {

    public ClaimLockedException(String lockedBy) {
        super("This claim is being edited by " + lockedBy + ".");
    }
}
