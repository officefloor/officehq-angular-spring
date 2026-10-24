package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A patron refused: the person themselves, or one they have already named. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonPatronException extends RuntimeException {
}
