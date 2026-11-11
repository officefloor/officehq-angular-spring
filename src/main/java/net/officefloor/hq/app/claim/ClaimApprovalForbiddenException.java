package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An approval or rejection of a PENDING claim by a user who may not: only a steward, and never its opener. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimApprovalForbiddenException extends RuntimeException {

    public ClaimApprovalForbiddenException(Long id, String user, String why) {
        super("Claim " + id + " cannot be decided by " + user + ": " + why);
    }
}
