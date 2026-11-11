package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A status change the claim's current status does not allow. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimStatusException extends RuntimeException {

    public ClaimStatusException(Long id, ClaimStatus from, ClaimStatus to) {
        super("Claim " + id + " cannot go from " + from + " to " + to);
    }
}
