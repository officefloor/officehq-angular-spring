package net.officefloor.hq.app.claim;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim id that is not on the sheet. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ClaimNotFoundException extends RuntimeException {

    public ClaimNotFoundException(String sheetId, Long id) {
        super("Claim " + id + " not found on sheet " + sheetId);
    }
}
