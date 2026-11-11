package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim re-dated by a user who may not: only a steward re-dates a claim. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimRedateForbiddenException extends RuntimeException {

    public ClaimRedateForbiddenException(Long id, String user) {
        super("Claim " + id + " cannot be re-dated by " + user + ": only a steward re-dates a claim");
    }
}
