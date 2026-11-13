package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A due-by set that is not allowed: by anyone but a steward, or of a claim that is not DRAFT, OPEN or PENDING. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimDueBySetException extends RuntimeException {

    public ClaimDueBySetException(String message) {
        super(message);
    }
}
