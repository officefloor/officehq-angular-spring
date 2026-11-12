package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An empty comment on a claim. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimCommentException extends RuntimeException {

    public ClaimCommentException(String message) {
        super(message);
    }
}
