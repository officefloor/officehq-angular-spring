package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A re-dating the claim does not allow. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimRedateException extends RuntimeException {

    public ClaimRedateException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
