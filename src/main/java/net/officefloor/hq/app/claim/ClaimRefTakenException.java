package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Giving a claim an external reference another claim on the sheet already has (compared without
 * regard to case). */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimRefTakenException extends RuntimeException {
}
