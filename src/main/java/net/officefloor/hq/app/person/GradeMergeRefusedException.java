package net.officefloor.hq.app.person;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A grade merge by someone who is not a steward, or of a grade into itself. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class GradeMergeRefusedException extends RuntimeException {

    public GradeMergeRefusedException(String sheetId, String from, String into, String reason) {
        super("The grade " + from + " cannot be merged into " + into + " on sheet " + sheetId + ": " + reason);
    }
}
