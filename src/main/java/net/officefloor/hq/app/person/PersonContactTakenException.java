package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Giving a person a contact another person on the sheet already has. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonContactTakenException extends RuntimeException {
}
