package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A share of a claim that is not allowed. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimShareException extends RuntimeException {

    public ClaimShareException(String message) {
        super(message);
    }
}
