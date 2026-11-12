package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An edit of a claim that is no longer a DRAFT: to change an open claim, void it and add a new one. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimNotDraftException extends RuntimeException {

    public ClaimNotDraftException(ClaimStatus status) {
        super("A " + status + " claim cannot be edited; only a DRAFT can. Void it and add a new one.");
    }
}
