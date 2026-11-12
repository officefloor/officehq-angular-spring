package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A parent the sheet cannot name: itself, a sheet that has it as an ancestor, or one never loaded. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ParentRefusedException extends RuntimeException {

    public ParentRefusedException(String message) {
        super(message);
    }
}
