package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim given an external reference already used on its sheet (compared without regard to case). */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimRefException extends RuntimeException {

    public ClaimRefException(String ref) {
        super("The reference " + ref + " is already used on the sheet");
    }
}
