package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An unretire refused: the person is not retired. */
@ResponseStatus(HttpStatus.CONFLICT)
public class UnretireRefusedException extends RuntimeException {

    public UnretireRefusedException(String message) {
        super(message);
    }
}
