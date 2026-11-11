package net.officefloor.hq.app.role;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Someone but a steward tried to delegate the steward role. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class DelegationForbiddenException extends RuntimeException {

    public DelegationForbiddenException(String sheetId, String user) {
        super(user + " is not a steward on sheet " + sheetId + " and may not delegate");
    }
}
