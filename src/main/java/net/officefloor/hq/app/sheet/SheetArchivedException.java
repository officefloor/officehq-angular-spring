package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** A change asked of an archived sheet: nothing on it may change. */
@ResponseStatus(HttpStatus.CONFLICT)
public class SheetArchivedException extends RuntimeException {
}
