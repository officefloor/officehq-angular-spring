package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A re-settle that is not allowed: by anyone but a steward, or of a claim that is not SETTLED. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimResettleException extends RuntimeException {

    public ClaimResettleException(String message) {
        super(message);
    }
}
