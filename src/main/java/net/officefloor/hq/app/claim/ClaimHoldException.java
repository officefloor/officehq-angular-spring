package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A hold or release the claim does not allow. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimHoldException extends RuntimeException {

    public ClaimHoldException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
