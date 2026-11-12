package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The sheet is archived: every change to it is refused. */
@ResponseStatus(HttpStatus.CONFLICT)
public class SheetArchivedException extends RuntimeException {

    public SheetArchivedException() {
        super("The sheet is archived.");
    }
}
