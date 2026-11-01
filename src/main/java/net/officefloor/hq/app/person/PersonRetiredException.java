package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The claim is dated after its person's retirement date. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
public class PersonRetiredException extends RuntimeException {}
