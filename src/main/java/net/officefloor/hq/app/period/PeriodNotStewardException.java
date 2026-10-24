package net.officefloor.hq.app.period;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A reopen asked by a user who is not a steward of the sheet. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class PeriodNotStewardException extends RuntimeException {
}
