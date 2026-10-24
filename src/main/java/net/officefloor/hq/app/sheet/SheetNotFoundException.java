package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An action on a sheet that was never loaded. */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class SheetNotFoundException extends RuntimeException {
}
