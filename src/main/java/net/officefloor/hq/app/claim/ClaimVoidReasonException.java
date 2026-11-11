package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim voided without a reason from the sheet's list of void reasons, while that list is not empty. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ClaimVoidReasonException extends RuntimeException {

    public ClaimVoidReasonException(Long id, String why) {
        super("Claim " + id + " cannot be voided: " + why);
    }
}
