package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A settle asked by the user who opened or last edited the claim: another user must settle it. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimSelfSettleException extends RuntimeException {
}
