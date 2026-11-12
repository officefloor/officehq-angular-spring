package net.officefloor.hq.app.crew;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A crew, or a member of one, that may not be added. */
@ResponseStatus(HttpStatus.CONFLICT)
public class CrewRefusedException extends RuntimeException {

    public CrewRefusedException(String message) {
        super(message);
    }
}
