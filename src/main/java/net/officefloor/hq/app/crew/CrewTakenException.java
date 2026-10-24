package net.officefloor.hq.app.crew;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A crew name asked that is already on the sheet. */
@ResponseStatus(HttpStatus.CONFLICT)
public class CrewTakenException extends RuntimeException {
}
