package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim dated after its person's retirement date cannot be added or moved there. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonRetiredException extends RuntimeException {

    public PersonRetiredException() {
        super("That date is after the person's retirement date.");
    }
}
