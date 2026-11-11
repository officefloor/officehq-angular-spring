package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A SETTLED claim voided after the days its settlement allows. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimVoidException extends RuntimeException {

    public ClaimVoidException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
