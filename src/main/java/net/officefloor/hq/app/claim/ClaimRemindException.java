package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A reminder for a claim that is not OPEN or PENDING, or for one already reminded on the sheet's today. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimRemindException extends RuntimeException {

    public ClaimRemindException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
