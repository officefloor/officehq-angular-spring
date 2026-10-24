package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A sheet change only a steward may make, asked by someone who is not one. */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class SheetNotStewardException extends RuntimeException {
}
