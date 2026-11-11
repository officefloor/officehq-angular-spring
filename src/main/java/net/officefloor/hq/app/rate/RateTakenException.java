package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Another user set the grade's first rate at the same moment. */
@ResponseStatus(HttpStatus.CONFLICT)
public class RateTakenException extends RuntimeException {

    public RateTakenException(String sheetId, String grade) {
        super("The rate for grade " + grade + " on sheet " + sheetId + " was set at the same moment");
    }
}
