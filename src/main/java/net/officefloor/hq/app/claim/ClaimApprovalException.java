package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An approval or rejection of a PENDING claim that is not allowed: by a clerk, by the user who opened
 *  the claim (approving), or with no reason (rejecting). */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimApprovalException extends RuntimeException {

    public ClaimApprovalException(String message) {
        super(message);
    }
}
