package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A clone the sheet cannot make: to a sheet that has been used, or to no id. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class CloneRefusedException extends RuntimeException {

    public CloneRefusedException(String message) {
        super(message);
    }
}
