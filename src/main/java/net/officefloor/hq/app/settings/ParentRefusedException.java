package net.officefloor.hq.app.settings;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A parent named for a sheet that is the sheet itself, that has the sheet as an ancestor, or that has
 * never been loaded. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ParentRefusedException extends RuntimeException {

    public ParentRefusedException(String message) {
        super(message);
    }
}
