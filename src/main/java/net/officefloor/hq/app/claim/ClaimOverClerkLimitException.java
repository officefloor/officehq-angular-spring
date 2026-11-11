package net.officefloor.hq.app.claim;

import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A clerk's settling of a claim whose due is above the sheet's clerk limit: a steward must settle it. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ClaimOverClerkLimitException extends RuntimeException {

    public ClaimOverClerkLimitException(Long id, BigDecimal due, BigDecimal limit) {
        super("Claim " + id + " has a due of " + due.toPlainString() + ", above the clerk limit of "
                + limit.toPlainString());
    }
}
