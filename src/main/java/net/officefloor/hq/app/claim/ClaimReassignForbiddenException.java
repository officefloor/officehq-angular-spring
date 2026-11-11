package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim reassigned by a user who may not: one whose role the sheet's permission matrix does not allow to reassign. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimReassignForbiddenException extends RuntimeException {

    public ClaimReassignForbiddenException(Long id, String user) {
        super("Claim " + id + " cannot be reassigned by " + user + ": the role is not allowed to reassign");
    }
}
