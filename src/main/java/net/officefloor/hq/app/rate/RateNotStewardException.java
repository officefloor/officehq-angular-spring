package net.officefloor.hq.app.rate;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A withdrawal of a rate asked by a user who is not a steward of the sheet. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class RateNotStewardException extends RuntimeException {
}
