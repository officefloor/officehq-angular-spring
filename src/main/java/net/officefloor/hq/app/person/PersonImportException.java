package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An import with a line the person form would refuse, or repeating a name or contact: no one is added. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonImportException extends RuntimeException {

    public PersonImportException(String message) {
        super(message);
    }
}
