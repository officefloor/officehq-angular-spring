package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The sheet was never loaded. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class SheetNotFoundException extends RuntimeException {

    public SheetNotFoundException() {
        super("No such sheet.");
    }
}
