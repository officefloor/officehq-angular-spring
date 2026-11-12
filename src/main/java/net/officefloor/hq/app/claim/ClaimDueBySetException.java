package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A due-by set the claim does not allow. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimDueBySetException extends RuntimeException {

    public ClaimDueBySetException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
