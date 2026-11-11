package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A sheet id that was never loaded. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class SheetNotFoundException extends RuntimeException {

    public SheetNotFoundException(String id) {
        super("Sheet " + id + " not found");
    }
}
