package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Opening a claim whose person already has as many OPEN or PENDING claims as the sheet's maximum allows. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimMaxOpenException extends RuntimeException {
}
