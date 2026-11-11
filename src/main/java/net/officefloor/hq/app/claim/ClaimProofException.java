package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A proof added to a claim that does not take one, or a claim settled or approved without the proof it needs. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ClaimProofException extends RuntimeException {

    public ClaimProofException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
