package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A paste with a line the claim form would refuse, or naming no one on the sheet: nothing is added. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimPasteException extends RuntimeException {

    public ClaimPasteException(String message) {
        super(message);
    }
}
