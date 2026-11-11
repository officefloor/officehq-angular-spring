package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A hold that cannot be added, such as one ending before it starts. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class HoldRefusedException extends RuntimeException {

    public HoldRefusedException(String message) {
        super(message);
    }
}
