package net.officefloor.hq.app.statement;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A statement as of a date it cannot be taken as of: after the sheet's today, or before the person was added. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class StatementDateException extends RuntimeException {

    public StatementDateException(String name, LocalDate asOf, String reason) {
        super("A statement of " + name + " cannot be taken as of " + asOf + ": " + reason);
    }
}
