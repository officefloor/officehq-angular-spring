package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim opened, settled or voided by a user whose role the sheet's permission matrix does not allow it. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimActionForbiddenException extends RuntimeException {

    public ClaimActionForbiddenException(Long id, String user, String action) {
        super("Claim " + id + " cannot be given " + action + " by " + user + ": the role is not allowed to " + action);
    }
}
