package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Another person on the sheet already has the contact. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonContactTakenException extends RuntimeException {

    public PersonContactTakenException() {
        super("That contact is already in use.");
    }
}
