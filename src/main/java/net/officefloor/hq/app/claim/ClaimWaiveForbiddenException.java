package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim's late charge waived by a user who may not: only a steward waives it. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimWaiveForbiddenException extends RuntimeException {

    public ClaimWaiveForbiddenException(Long id, String user) {
        super("Claim " + id + " cannot have its late charge waived by " + user + ": only a steward waives it");
    }
}
