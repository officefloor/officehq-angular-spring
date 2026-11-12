package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A reminder of a claim that is not OPEN or PENDING, or of a claim already reminded on the sheet's today. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimRemindException extends RuntimeException {

    public ClaimRemindException(String message) {
        super(message);
    }
}
