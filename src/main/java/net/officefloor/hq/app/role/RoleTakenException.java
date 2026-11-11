package net.officefloor.hq.app.role;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Another user set the same user's role at the same moment. */
@ResponseStatus(HttpStatus.CONFLICT)
public class RoleTakenException extends RuntimeException {

    public RoleTakenException(String sheetId, String user) {
        super("The role of " + user + " on sheet " + sheetId + " was set at the same moment");
    }
}
