package net.officefloor.hq.app.history;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** The screen shows the sheet as of a date: every change from it is refused until the date is cleared. */
@ResponseStatus(HttpStatus.CONFLICT)
public class AsOfException extends RuntimeException {

    public AsOfException() {
        super("The sheet is shown as of a date: clear it to make a change.");
    }
}
