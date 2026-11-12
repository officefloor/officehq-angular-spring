package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Another person on the sheet already has the name, compared without regard to case. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonNameTakenException extends RuntimeException {

    public PersonNameTakenException() {
        super("That name is already in use.");
    }
}
