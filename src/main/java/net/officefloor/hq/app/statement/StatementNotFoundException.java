package net.officefloor.hq.app.statement;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A cancel of the last statement of a person who has none. */
@ResponseStatus(HttpStatus.CONFLICT)
public class StatementNotFoundException extends RuntimeException {
}
