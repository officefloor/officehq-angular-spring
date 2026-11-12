package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A proof added to a claim that takes none, or a settle or approval of a claim above the proof threshold
 *  with no proof. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimProofException extends RuntimeException {

    public ClaimProofException(String message) {
        super(message);
    }
}
