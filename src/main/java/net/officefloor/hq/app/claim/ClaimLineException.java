package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim line refused: an empty label, or a total the claim's amount cannot hold. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ClaimLineException extends RuntimeException {

    public ClaimLineException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
