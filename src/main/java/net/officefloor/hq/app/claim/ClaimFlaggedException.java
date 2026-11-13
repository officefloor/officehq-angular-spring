package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An open, by anyone but a steward, of a claim whose person is flagged on the sheet's today. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimFlaggedException extends RuntimeException {

    public ClaimFlaggedException(String message) {
        super(message);
    }
}
