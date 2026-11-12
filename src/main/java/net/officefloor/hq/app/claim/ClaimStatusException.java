package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The claim's status does not allow the change asked for. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimStatusException extends RuntimeException {

    public ClaimStatusException(ClaimStatus from, ClaimStatus to) {
        super("A " + from + " claim cannot become " + to + ".");
    }
}
