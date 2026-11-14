package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Cloning the sheet was refused: only a steward may, and only to a sheet never used. */
@ResponseStatus(HttpStatus.CONFLICT)
public class CloneRefusedException extends RuntimeException {

    public CloneRefusedException(String message) {
        super(message);
    }
}
