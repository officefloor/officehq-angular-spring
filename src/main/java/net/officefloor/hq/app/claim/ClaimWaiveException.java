package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A waiving of the late charge the claim does not allow. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimWaiveException extends RuntimeException {

    public ClaimWaiveException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
