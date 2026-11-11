package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An edit of a claim whose editor another user has open: it is locked until they save or cancel it. */
@ResponseStatus(HttpStatus.LOCKED)
public class ClaimLockedException extends RuntimeException {

    public ClaimLockedException(Long id, String lockedBy) {
        super("Claim " + id + " is being edited by " + lockedBy);
    }
}
