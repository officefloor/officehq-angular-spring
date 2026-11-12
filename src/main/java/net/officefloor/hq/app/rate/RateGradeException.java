package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A rate set for a grade that is not one of the sheet's grades. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class RateGradeException extends RuntimeException {

    public RateGradeException(String sheetId, String grade) {
        super("The grade " + grade + " is not a grade of sheet " + sheetId);
    }
}
