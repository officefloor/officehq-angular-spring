package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A delete of a claim that is not a DRAFT, or a restore of a claim that is not DELETED or was deleted
 *  before the sheet's today. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimDeleteException extends RuntimeException {

    public ClaimDeleteException(String message) {
        super(message);
    }
}
