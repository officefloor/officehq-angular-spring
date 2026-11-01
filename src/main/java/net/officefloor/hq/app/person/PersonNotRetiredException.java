package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only a retired person can be unretired. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonNotRetiredException extends RuntimeException {}
