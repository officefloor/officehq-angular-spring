package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pasted line of people is not one the person form would accept. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class PersonImportRefusedException extends RuntimeException {}
