package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A reminder of a claim already reminded on the sheet's today. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class ClaimRemindedException extends RuntimeException {
}
