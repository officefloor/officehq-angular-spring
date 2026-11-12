package net.officefloor.hq.app.setting;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A grade that is not one of the sheet's grades, or is already retired. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class GradeRetireException extends RuntimeException {

    public GradeRetireException(String sheetId, String grade, String reason) {
        super("The grade " + grade + " cannot be retired on sheet " + sheetId + ": " + reason);
    }
}
