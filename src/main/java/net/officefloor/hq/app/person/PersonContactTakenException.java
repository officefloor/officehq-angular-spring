package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A contact already given to another person on the sheet. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class PersonContactTakenException extends RuntimeException {

    public PersonContactTakenException(String sheetId, String contact) {
        super("The contact " + contact + " is already in use on sheet " + sheetId);
    }
}
