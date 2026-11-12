package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A clerk asked to settle a claim whose due is above the sheet's clerk limit. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimLimitException extends RuntimeException {

    public ClaimLimitException() {
        super("This claim's due is above the clerk limit; only a steward can settle it.");
    }
}
