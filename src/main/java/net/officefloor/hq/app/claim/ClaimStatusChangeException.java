package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A status change a claim in its current status does not allow. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimStatusChangeException extends RuntimeException {
}
