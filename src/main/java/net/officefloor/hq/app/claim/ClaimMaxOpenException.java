package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim opened while its person already has as many OPEN or PENDING claims as the sheet's maximum of open claims. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimMaxOpenException extends RuntimeException {

    public ClaimMaxOpenException(Long id, int max) {
        super("Claim " + id + ": its person already has " + max + " open claims, the sheet's maximum");
    }
}
