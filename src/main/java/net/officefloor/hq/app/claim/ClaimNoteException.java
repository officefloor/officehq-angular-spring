package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An open of a claim with an empty note whose due is above the note threshold. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ClaimNoteException extends RuntimeException {

    public ClaimNoteException(String message) {
        super(message);
    }
}
