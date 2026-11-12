package net.officefloor.hq.app.setting;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A grade code that is not one to three capital letters, is already one of the sheet's grades, or is not one of them. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class GradeAddException extends RuntimeException {

    public GradeAddException(String sheetId, String grade, String reason) {
        super("The grade " + grade + " is refused on sheet " + sheetId + ": " + reason);
    }
}
