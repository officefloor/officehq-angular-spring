package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only a steward can flag a person, and only with a reason and an until date. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PersonFlagRefusedException extends RuntimeException {}
