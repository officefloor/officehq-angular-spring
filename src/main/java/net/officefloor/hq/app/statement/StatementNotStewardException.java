package net.officefloor.hq.app.statement;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A cancel of a statement asked by a user who is not a steward of the sheet. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class StatementNotStewardException extends RuntimeException {
}
