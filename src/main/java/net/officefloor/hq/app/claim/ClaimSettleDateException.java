package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A settle as of a date after the sheet's today, or of a claim not on the sheet then. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimSettleDateException extends RuntimeException {

    public ClaimSettleDateException(String message) {
        super(message);
    }
}
