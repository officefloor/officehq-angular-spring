package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A comment refused: an empty one. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ClaimCommentException extends RuntimeException {

    public ClaimCommentException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
