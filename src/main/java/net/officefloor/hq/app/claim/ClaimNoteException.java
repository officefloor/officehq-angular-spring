package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim opened with an empty note while its due is above the note threshold. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ClaimNoteException extends RuntimeException {

    public ClaimNoteException(Long id, String why) {
        super("Claim " + id + ": " + why);
    }
}
