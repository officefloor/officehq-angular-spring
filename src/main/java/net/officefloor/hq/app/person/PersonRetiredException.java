package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim dated after its person's retirement date, or within one of their past retirements, cannot be added or
 *  moved there. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonRetiredException extends RuntimeException {

    public PersonRetiredException() {
        super("That date falls in the person's retirement.");
    }
}
