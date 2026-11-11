package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A merge refused: not by a steward, into the same person, or with either person retired. */
@ResponseStatus(HttpStatus.CONFLICT)
public class PersonMergeRefusedException extends RuntimeException {

    public PersonMergeRefusedException(String message) {
        super(message);
    }
}
