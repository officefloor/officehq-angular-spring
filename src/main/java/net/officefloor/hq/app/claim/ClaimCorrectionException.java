package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A negative amount that does not name, by number, a SETTLED claim of the same person to correct; or a
 *  claim of 0.00 or more that names one. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimCorrectionException extends RuntimeException {

    public ClaimCorrectionException(String message) {
        super(message);
    }
}
