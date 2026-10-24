package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A watch refused: the user already watches the person; or an unwatch of one they do not watch. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonWatchException extends RuntimeException {
}
