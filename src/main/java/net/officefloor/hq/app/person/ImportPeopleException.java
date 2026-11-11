package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pasted line of people that the person form would refuse. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ImportPeopleException extends RuntimeException {

    public ImportPeopleException(int line, String message) {
        super("Line " + line + ": " + message);
    }

    public ImportPeopleException(String message) {
        super(message);
    }
}
