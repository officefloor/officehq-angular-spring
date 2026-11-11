package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A negative amount (a correction) that does not name, by number, a SETTLED claim of the same person; or a number to
 * correct given with an amount that is not negative.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ClaimCorrectionException extends RuntimeException {

    public ClaimCorrectionException(String message) {
        super(message);
    }
}
