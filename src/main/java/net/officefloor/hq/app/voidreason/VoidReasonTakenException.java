package net.officefloor.hq.app.voidreason;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The reason is already on the sheet's list of void reasons. */
@ResponseStatus(HttpStatus.CONFLICT)
public class VoidReasonTakenException extends RuntimeException {

    public VoidReasonTakenException(String sheetId, String reason) {
        super(reason + " is already a void reason on sheet " + sheetId);
    }
}
