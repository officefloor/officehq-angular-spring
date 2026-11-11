package net.officefloor.hq.app.role;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A viewer tried to change something on the sheet. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ViewerForbiddenException extends RuntimeException {

    public ViewerForbiddenException(String sheetId, String user) {
        super(user + " is a viewer on sheet " + sheetId + " and may change nothing");
    }
}
