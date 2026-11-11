package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim reassigned by a user who may not: only a steward moves a claim to another person. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimReassignForbiddenException extends RuntimeException {

    public ClaimReassignForbiddenException(Long id, String user) {
        super("Claim " + id + " cannot be reassigned by " + user + ": only a steward reassigns a claim");
    }
}
