package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A hold asked of a person already held, or a release of one not held. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonHoldException extends RuntimeException {
}
