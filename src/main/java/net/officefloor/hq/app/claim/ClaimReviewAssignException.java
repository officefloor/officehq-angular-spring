package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A review assigned on a claim not in REVIEW. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimReviewAssignException extends RuntimeException {

    public ClaimReviewAssignException(Long id, ClaimStatus status) {
        super("Claim " + id + " is " + status + ": only a REVIEW claim can have its review assigned");
    }
}
