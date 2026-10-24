package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A clone of a sheet asked by someone who is not a steward, or to a sheet already used. */
@ResponseStatus(HttpStatus.CONFLICT)
public class SheetCloneException extends RuntimeException {
}
