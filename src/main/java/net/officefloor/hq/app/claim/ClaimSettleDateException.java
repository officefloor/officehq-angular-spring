package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim settled as of a date after the sheet's today, or as of a date before the claim existed. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimSettleDateException extends RuntimeException {

    public ClaimSettleDateException(Long id, String why) {
        super("Claim " + id + " cannot be settled as of that date: " + why);
    }
}
