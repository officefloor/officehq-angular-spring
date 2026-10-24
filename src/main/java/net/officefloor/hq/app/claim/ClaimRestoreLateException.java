package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A restore of a DELETED claim asked on a sheet-today other than the one it was deleted on. */
@ResponseStatus(HttpStatus.GONE)
public class ClaimRestoreLateException extends RuntimeException {
}
