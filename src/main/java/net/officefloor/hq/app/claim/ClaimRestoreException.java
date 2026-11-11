package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A DELETED claim restored on a later day than it was deleted. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimRestoreException extends RuntimeException {

    public ClaimRestoreException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
