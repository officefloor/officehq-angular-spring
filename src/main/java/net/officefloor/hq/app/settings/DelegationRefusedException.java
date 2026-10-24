package net.officefloor.hq.app.settings;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A delegation asked by a user who is not a steward of the sheet, or whose to-date is before its from-date. */
@ResponseStatus(HttpStatus.CONFLICT)
public class DelegationRefusedException extends RuntimeException {

    public DelegationRefusedException(String message) {
        super(message);
    }
}
