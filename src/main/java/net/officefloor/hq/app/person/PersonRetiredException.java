package net.officefloor.hq.app.person;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A claim dated after the retirement date of the person it is for. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonRetiredException extends RuntimeException {

    public PersonRetiredException(String name, LocalDate retiredFrom, LocalDate date) {
        super(name + " is retired from " + retiredFrom + ": no claim can be dated " + date);
    }
}
