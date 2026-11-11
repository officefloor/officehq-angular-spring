package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An edit of a claim that is no longer a DRAFT: to change an OPEN claim, void it and add a new one. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimNotEditableException extends RuntimeException {

    public ClaimNotEditableException(Long id, ClaimStatus status) {
        super("Claim " + id + " is " + status + ": only a DRAFT can be edited");
    }
}
