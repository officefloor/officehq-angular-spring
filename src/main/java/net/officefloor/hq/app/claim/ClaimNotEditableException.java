package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An edit of a claim that is no longer a DRAFT: an open claim is changed by voiding it and adding a
 * new one. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimNotEditableException extends RuntimeException {
}
