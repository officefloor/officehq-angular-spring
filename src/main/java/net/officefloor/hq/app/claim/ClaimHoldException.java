package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A hold or release of a claim that is not OPEN or PENDING. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimHoldException extends RuntimeException {

    public ClaimHoldException(String message) {
        super(message);
    }
}
