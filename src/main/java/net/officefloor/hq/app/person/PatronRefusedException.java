package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A person may not be their own patron, nor name the same patron twice. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PatronRefusedException extends RuntimeException {}
