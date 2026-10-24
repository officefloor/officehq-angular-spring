package net.officefloor.hq.app.columns;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A column asked that is not one of the people list's choosable columns. */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ColumnUnknownException extends RuntimeException {
}
