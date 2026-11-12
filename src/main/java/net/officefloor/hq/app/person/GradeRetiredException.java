package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A retired grade chosen for a person. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class GradeRetiredException extends RuntimeException {

    public GradeRetiredException(String sheetId, String grade) {
        super("The grade " + grade + " is retired on sheet " + sheetId);
    }
}
