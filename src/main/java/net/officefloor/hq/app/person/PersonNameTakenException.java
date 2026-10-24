package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Adding or renaming a person to a name another person on the sheet already has. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonNameTakenException extends RuntimeException {
}
