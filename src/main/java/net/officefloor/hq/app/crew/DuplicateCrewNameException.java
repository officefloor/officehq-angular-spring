package net.officefloor.hq.app.crew;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The name is already a crew of the sheet. */
@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateCrewNameException extends RuntimeException {}
