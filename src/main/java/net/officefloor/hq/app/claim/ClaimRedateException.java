package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A re-date that is not allowed: by anyone but a steward, or of a claim that is not OPEN or PENDING. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimRedateException extends RuntimeException {

    public ClaimRedateException(String message) {
        super(message);
    }
}
