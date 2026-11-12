package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A flag that cannot be set, such as one with a blank reason or no until-date. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class FlagRefusedException extends RuntimeException {

    public FlagRefusedException(String message) {
        super(message);
    }
}
