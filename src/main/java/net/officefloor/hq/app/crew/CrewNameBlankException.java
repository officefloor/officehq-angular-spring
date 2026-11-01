package net.officefloor.hq.app.crew;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A crew's name cannot be blank. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class CrewNameBlankException extends RuntimeException {}
