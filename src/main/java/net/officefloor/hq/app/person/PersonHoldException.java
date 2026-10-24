package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A hold that ends before it starts. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonHoldException extends RuntimeException {
}
