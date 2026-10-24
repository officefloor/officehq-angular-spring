package net.officefloor.hq.app.crew;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A crew name asked that is blank. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class CrewBlankException extends RuntimeException {
}
