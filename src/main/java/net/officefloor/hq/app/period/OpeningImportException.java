package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pasted line of opening balances naming no person or period on the sheet, or an amount that is not one. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class OpeningImportException extends RuntimeException {

    public OpeningImportException(int line, String message) {
        super("Line " + line + ": " + message);
    }

    public OpeningImportException(String message) {
        super(message);
    }
}
