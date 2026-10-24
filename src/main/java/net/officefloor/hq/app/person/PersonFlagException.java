package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A flag refused: by a user who is not a steward, or with a blank reason or no until date. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonFlagException extends RuntimeException {
}
