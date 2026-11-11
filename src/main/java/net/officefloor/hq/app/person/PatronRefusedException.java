package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A patron a person cannot name: themselves, or someone already their patron. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PatronRefusedException extends RuntimeException {

    public PatronRefusedException(String message) {
        super(message);
    }
}
