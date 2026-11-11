package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pasted line of claims that cannot be read, or that names no one on the sheet. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class PasteClaimsException extends RuntimeException {

    public PasteClaimsException(int line, String message) {
        super("Line " + line + ": " + message);
    }

    public PasteClaimsException(String message) {
        super(message);
    }
}
