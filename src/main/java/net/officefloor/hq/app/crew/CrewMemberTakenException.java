package net.officefloor.hq.app.crew;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A member asked of a crew that is its lead or already in it. */
@ResponseStatus(HttpStatus.CONFLICT)
public class CrewMemberTakenException extends RuntimeException {
}
