package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A move up from the top of the list or down from the bottom of it. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonMoveRefusedException extends RuntimeException {

    public PersonMoveRefusedException(String message) {
        super(message);
    }
}
