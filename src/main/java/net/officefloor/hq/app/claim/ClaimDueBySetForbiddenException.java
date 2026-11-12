package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim whose due-by is set by a user who may not: only a steward sets a due-by. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimDueBySetForbiddenException extends RuntimeException {

    public ClaimDueBySetForbiddenException(Long id, String user) {
        super("Claim " + id + " cannot have its due-by set by " + user + ": only a steward sets a due-by");
    }
}
