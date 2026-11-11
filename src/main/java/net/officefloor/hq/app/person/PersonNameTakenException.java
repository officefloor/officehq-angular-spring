package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A name already used by another person on the sheet. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonNameTakenException extends RuntimeException {

    public PersonNameTakenException(String sheetId, String name) {
        super("The name " + name + " is already in use on sheet " + sheetId);
    }
}
