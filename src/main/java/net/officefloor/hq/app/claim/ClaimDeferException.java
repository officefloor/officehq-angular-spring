package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A deferral the claim does not allow, or a deferred claim settled before the date it is deferred until. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimDeferException extends RuntimeException {

    public ClaimDeferException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
