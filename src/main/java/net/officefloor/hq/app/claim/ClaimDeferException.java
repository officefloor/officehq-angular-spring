package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A deferral that is not allowed, or a settle of a claim deferred past the sheet's today. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimDeferException extends RuntimeException {

    public ClaimDeferException(String message) {
        super(message);
    }
}
