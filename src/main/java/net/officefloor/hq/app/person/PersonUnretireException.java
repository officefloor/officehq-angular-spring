package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An unretire of a person who is not retired. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonUnretireException extends RuntimeException {
}
