package net.officefloor.hq.app.sheet;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Only a steward can clone the sheet, and only to a sheet never used. */
@ResponseStatus(HttpStatus.CONFLICT)
public class SheetCloneRefusedException extends RuntimeException {}
