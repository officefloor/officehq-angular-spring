package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** An archived sheet can change nothing. */
@ResponseStatus(HttpStatus.CONFLICT)
public class SheetArchivedException extends RuntimeException {}
