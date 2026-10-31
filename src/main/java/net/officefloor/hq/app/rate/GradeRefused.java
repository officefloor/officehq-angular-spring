package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A grade was refused (a retired grade chosen, or a grade retired twice): nothing was written. */
public class GradeRefused extends ResponseStatusException {

    public GradeRefused(String message) {
        super(HttpStatus.CONFLICT, message);
    }
}
