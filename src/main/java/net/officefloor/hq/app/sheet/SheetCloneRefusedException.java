package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A clone refused: not by a steward, or to a sheet id already used. */
@ResponseStatus(HttpStatus.CONFLICT)
public class SheetCloneRefusedException extends RuntimeException {

    public SheetCloneRefusedException(String message) {
        super(message);
    }
}
