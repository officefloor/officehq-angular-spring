package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A waiver of a claim's late charge by anyone but a steward, of a claim that is not OPEN or PENDING, or of a
 *  claim already waived. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimWaiveException extends RuntimeException {

    public ClaimWaiveException(String message) {
        super(message);
    }
}
