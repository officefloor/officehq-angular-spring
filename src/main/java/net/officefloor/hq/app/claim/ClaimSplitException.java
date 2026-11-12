package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A split of a claim that cannot be split, or of an amount it cannot take. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimSplitException extends RuntimeException {

    public ClaimSplitException(String message) {
        super(message);
    }
}
