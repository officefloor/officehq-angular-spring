package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An external reference already used by another claim on the sheet (compared without regard to case). */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimRefException extends RuntimeException {

    public ClaimRefException(String message) {
        super(message);
    }
}
