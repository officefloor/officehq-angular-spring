package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A split the claim does not allow: it is not OPEN, is deferred, or the amount is not within its own. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimSplitException extends RuntimeException {

    public ClaimSplitException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
