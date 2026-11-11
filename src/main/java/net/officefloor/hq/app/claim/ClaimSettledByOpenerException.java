package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim's settling by the same user who opened it: another user must settle it. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ClaimSettledByOpenerException extends RuntimeException {

    public ClaimSettledByOpenerException(Long id, String user) {
        super("Claim " + id + " was opened by " + user + ", who cannot also settle it");
    }
}
