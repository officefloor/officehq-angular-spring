package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A grade chosen for a person that is retired, or is not one of the sheet's grades. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class GradeRetiredException extends RuntimeException {

    public GradeRetiredException(String sheetId, String grade) {
        super("The grade " + grade + " is retired on, or not a grade of, sheet " + sheetId);
    }
}
