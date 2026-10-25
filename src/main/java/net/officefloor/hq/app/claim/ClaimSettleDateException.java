package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A settle of a claim as of a date after the sheet's today, or one before the claim was added. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ClaimSettleDateException extends RuntimeException {
}
