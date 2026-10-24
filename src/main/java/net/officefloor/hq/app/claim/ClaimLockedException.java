package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An edit of a claim whose editor another user has open: it is locked for everyone else until that
 * editor is saved or cancelled, or the sheet's today changes. */
@ResponseStatus(HttpStatus.PRECONDITION_FAILED)
public class ClaimLockedException extends RuntimeException {
}
