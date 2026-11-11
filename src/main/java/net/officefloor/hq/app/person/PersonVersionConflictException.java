package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A save of a version of a person that someone else has since saved over. */
@ResponseStatus(HttpStatus.PRECONDITION_FAILED)
public class PersonVersionConflictException extends RuntimeException {

    public PersonVersionConflictException(String sheetId, Long id) {
        super("Person " + id + " on sheet " + sheetId + " was saved by someone else in the meantime");
    }
}
