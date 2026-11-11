package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim re-settled by a user who may not: only a steward re-settles a claim. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimResettleForbiddenException extends RuntimeException {

    public ClaimResettleForbiddenException(Long id, String user) {
        super("Claim " + id + " cannot be re-settled by " + user + ": only a steward re-settles a claim");
    }
}
