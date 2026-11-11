package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A pasted line of rates that the rate form would refuse. */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ImportRatesException extends RuntimeException {

    public ImportRatesException(int line, String message) {
        super("Line " + line + ": " + message);
    }

    public ImportRatesException(String message) {
        super(message);
    }
}
