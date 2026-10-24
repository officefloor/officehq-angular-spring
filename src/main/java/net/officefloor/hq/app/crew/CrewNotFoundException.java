package net.officefloor.hq.app.crew;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A crew asked that is not on the sheet. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class CrewNotFoundException extends RuntimeException {
}
