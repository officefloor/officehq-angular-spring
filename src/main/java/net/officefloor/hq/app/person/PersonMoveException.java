package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A move up from the top of the list, or down from its bottom. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonMoveException extends RuntimeException {
}
