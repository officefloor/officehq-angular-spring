package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim opened while its person already has as many OPEN or PENDING claims in its period as the period caps. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimCapException extends RuntimeException {

    public ClaimCapException(Long id, String period, int cap) {
        super("Claim " + id + ": its person already has " + cap + " open claims in the period " + period
                + ", its cap");
    }
}
